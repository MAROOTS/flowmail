package com.flowmail.campaigns.service;

import com.flowmail.campaigns.CampaignResponse;
import com.flowmail.campaigns.CreateCampaignRequest;
import com.flowmail.campaigns.MessageResponse;
import com.flowmail.common.ApiException;
import com.flowmail.tenancy.TenantTx;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CampaignService {

    private static final int MAX_PAGE_SIZE = 100;

    private static final RowMapper<CampaignResponse> MAPPER = (rs, i) -> new CampaignResponse(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getObject("template_id", UUID.class),
            rs.getString("from_name"),
            rs.getString("from_email"),
            rs.getString("status"),
            rs.getInt("recipient_count"),
            toInstant(rs.getObject("launched_at", OffsetDateTime.class)),
            toInstant(rs.getObject("created_at", OffsetDateTime.class)),
            Map.of());

    private record StatusCount(String status, int count) {
    }

    private record LaunchRow(String status, UUID templateId) {
    }

    private record TemplateRow(String subject, String htmlBody, String textBody) {
    }

    private final JdbcClient jdbc;
    private final TenantTx tenantTx;
    private final UsageService usageService;
    private final TemplateRenderer renderer;

    public CampaignResponse create(UUID tenantId, UUID userId, CreateCampaignRequest request) {
        return tenantTx.call(tenantId, () -> {
            boolean templateExists = jdbc.sql("""
                    select exists (select 1 from templates where tenant_id = :tenant and id = :id)
                    """)
                    .param("tenant", tenantId)
                    .param("id", request.templateId())
                    .query(Boolean.class)
                    .single();
            if (!templateExists) {
                throw new ApiException(HttpStatus.NOT_FOUND, "template_not_found", "Template not found");
            }

            return jdbc.sql("""
                    insert into campaigns (tenant_id, name, template_id, from_name, from_email, created_by)
                    values (:tenant, :name, :template, :fromName, :fromEmail, :user)
                    returning id, name, template_id, from_name, from_email, status,
                              recipient_count, launched_at, created_at
                    """)
                    .param("tenant", tenantId)
                    .param("name", request.name().trim())
                    .param("template", request.templateId())
                    .param("fromName", request.fromName().trim())
                    .param("fromEmail", request.fromEmail().trim().toLowerCase(Locale.ROOT))
                    .param("user", userId)
                    .query(MAPPER)
                    .single();
        });
    }

    public List<CampaignResponse> list(UUID tenantId, int limit, int offset) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        int start = Math.max(offset, 0);

        return tenantTx.call(tenantId, () -> jdbc.sql("""
                select id, name, template_id, from_name, from_email, status,
                       recipient_count, launched_at, created_at
                from campaigns
                where tenant_id = :tenant
                order by created_at desc, id
                limit :limit offset :offset
                """)
                .param("tenant", tenantId)
                .param("limit", pageSize)
                .param("offset", start)
                .query(MAPPER)
                .list());
    }

    public CampaignResponse get(UUID tenantId, UUID id) {
        return tenantTx.call(tenantId, () -> withCounts(tenantId, load(tenantId, id)));
    }

    public List<MessageResponse> messages(UUID tenantId, UUID campaignId, int limit, int offset) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        int start = Math.max(offset, 0);

        return tenantTx.call(tenantId, () -> {
            load(tenantId, campaignId);
            return jdbc.sql("""
                    select id, email, status, attempt_count, last_error, sent_at
                    from email_messages
                    where tenant_id = :tenant and campaign_id = :campaign
                    order by email, id
                    limit :limit offset :offset
                    """)
                    .param("tenant", tenantId)
                    .param("campaign", campaignId)
                    .param("limit", pageSize)
                    .param("offset", start)
                    .query((rs, i) -> new MessageResponse(
                            rs.getObject("id", UUID.class),
                            rs.getString("email"),
                            rs.getString("status"),
                            rs.getInt("attempt_count"),
                            rs.getString("last_error"),
                            toInstant(rs.getObject("sent_at", OffsetDateTime.class))))
                    .list();
        });
    }

    /**
     * Everything below happens in one transaction: lock the campaign, snapshot the template,
     * create one message per eligible contact, reserve quota, flip the status. Any failure
     * rolls all of it back, so a campaign is either fully queued or still a draft.
     */
    public CampaignResponse launch(UUID tenantId, UUID campaignId) {
        return tenantTx.call(tenantId, () -> {
            LaunchRow campaign = jdbc.sql("""
                    select status, template_id
                    from campaigns
                    where tenant_id = :tenant and id = :id
                    for update
                    """)
                    .param("tenant", tenantId)
                    .param("id", campaignId)
                    .query((rs, i) -> new LaunchRow(rs.getString("status"), rs.getObject("template_id", UUID.class)))
                    .optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "campaign_not_found", "Campaign not found"));

            if (!"draft".equals(campaign.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "campaign_not_draft",
                        "Only draft campaigns can be launched");
            }

            TemplateRow template = jdbc.sql("""
                    select subject, html_body, text_body
                    from templates
                    where tenant_id = :tenant and id = :id
                    """)
                    .param("tenant", tenantId)
                    .param("id", campaign.templateId())
                    .query((rs, i) -> new TemplateRow(
                            rs.getString("subject"),
                            rs.getString("html_body"),
                            rs.getString("text_body")))
                    .single();

            if (!renderer.variablesIn(template.htmlBody()).contains("unsubscribeUrl")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "missing_unsubscribe_link",
                        "The template must include {{unsubscribeUrl}} before it can be sent");
            }

            int recipients = jdbc.sql("""
                    insert into email_messages (tenant_id, campaign_id, contact_id, email)
                    select c.tenant_id, :campaign, c.id, c.email
                    from contacts c
                    where c.tenant_id = :tenant
                      and c.status = 'subscribed'
                      and not exists (
                          select 1 from suppression_entries s
                          where s.tenant_id = c.tenant_id and s.email = c.email)
                    """)
                    .param("tenant", tenantId)
                    .param("campaign", campaignId)
                    .update();

            if (recipients == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "no_recipients",
                        "There are no subscribed, unsuppressed contacts to send to");
            }

            usageService.reserve(tenantId, recipients);

            jdbc.sql("""
                    update campaigns
                    set status = 'queued', recipient_count = :count, launched_at = now(),
                        updated_at = now(), snapshot_subject = :subject,
                        snapshot_html = :html, snapshot_text = :text
                    where tenant_id = :tenant and id = :id
                    """)
                    .param("tenant", tenantId)
                    .param("id", campaignId)
                    .param("count", recipients)
                    .param("subject", template.subject())
                    .param("html", template.htmlBody())
                    .param("text", template.textBody())
                    .update();

            return withCounts(tenantId, load(tenantId, campaignId));
        });
    }

    private CampaignResponse load(UUID tenantId, UUID id) {
        return jdbc.sql("""
                select id, name, template_id, from_name, from_email, status,
                       recipient_count, launched_at, created_at
                from campaigns
                where tenant_id = :tenant and id = :id
                """)
                .param("tenant", tenantId)
                .param("id", id)
                .query(MAPPER)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign_not_found", "Campaign not found"));
    }

    private CampaignResponse withCounts(UUID tenantId, CampaignResponse campaign) {
        List<StatusCount> rows = jdbc.sql("""
                select status, count(*) as n
                from email_messages
                where tenant_id = :tenant and campaign_id = :campaign
                group by status
                order by status
                """)
                .param("tenant", tenantId)
                .param("campaign", campaign.id())
                .query((rs, i) -> new StatusCount(rs.getString("status"), rs.getInt("n")))
                .list();

        Map<String, Integer> counts = new LinkedHashMap<>();
        rows.forEach(row -> counts.put(row.status(), row.count()));
        return campaign.withCounts(counts);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}