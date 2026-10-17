package com.flowmail.campaigns.service;

import com.flowmail.campaigns.TemplatePreview;
import com.flowmail.campaigns.TemplateRequest;
import com.flowmail.campaigns.TemplateResponse;
import com.flowmail.campaigns.TemplateSummary;
import com.flowmail.common.ApiException;
import com.flowmail.tenancy.TenantTx;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TemplateService {

    private static final int MAX_PAGE_SIZE = 100;

    private static final Map<String, String> SAMPLE_VALUES = Map.of(
            "firstName", "Alex",
            "lastName", "Sample",
            "email", "alex@example.com",
            "unsubscribeUrl", "https://example.com/unsubscribe/preview");

    private static final RowMapper<TemplateResponse> MAPPER = (rs, i) -> new TemplateResponse(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("subject"),
            rs.getString("html_body"),
            rs.getString("text_body"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final TenantTx tenantTx;
    private final TemplateRenderer renderer;

    public TemplateResponse create(UUID tenantId, UUID userId, TemplateRequest request) {
        validateVariables(request);
        String textBody = blankToNull(request.textBody());

        return tenantTx.call(tenantId, () -> {
            try {
                return jdbc.sql("""
                        insert into templates (tenant_id, name, subject, html_body, text_body, created_by)
                        values (:tenant, :name, :subject, :html, :text, :user)
                        returning id, name, subject, html_body, text_body, created_at, updated_at
                        """)
                        .param("tenant", tenantId)
                        .param("name", request.name().trim())
                        .param("subject", request.subject())
                        .param("html", request.htmlBody())
                        .param("text", textBody)
                        .param("user", userId)
                        .query(MAPPER)
                        .single();
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "template_exists",
                        "A template with this name already exists");
            }
        });
    }

    public List<TemplateSummary> list(UUID tenantId, int limit, int offset) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        int start = Math.max(offset, 0);

        return tenantTx.call(tenantId, () -> jdbc.sql("""
                select id, name, subject, updated_at
                from templates
                where tenant_id = :tenant
                order by name, id
                limit :limit offset :offset
                """)
                .param("tenant", tenantId)
                .param("limit", pageSize)
                .param("offset", start)
                .query((rs, i) -> new TemplateSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("subject"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .list());
    }

    public TemplateResponse get(UUID tenantId, UUID id) {
        return tenantTx.call(tenantId, () -> jdbc.sql("""
                select id, name, subject, html_body, text_body, created_at, updated_at
                from templates
                where tenant_id = :tenant and id = :id
                """)
                        .param("tenant", tenantId)
                        .param("id", id)
                        .query(MAPPER)
                        .optional())
                .orElseThrow(() -> notFound());
    }

    public TemplateResponse update(UUID tenantId, UUID id, TemplateRequest request) {
        validateVariables(request);
        String textBody = blankToNull(request.textBody());

        Optional<TemplateResponse> updated = tenantTx.call(tenantId, () -> {
            try {
                return jdbc.sql("""
                        update templates
                        set name = :name, subject = :subject, html_body = :html,
                            text_body = :text, updated_at = now()
                        where tenant_id = :tenant and id = :id
                        returning id, name, subject, html_body, text_body, created_at, updated_at
                        """)
                        .param("tenant", tenantId)
                        .param("id", id)
                        .param("name", request.name().trim())
                        .param("subject", request.subject())
                        .param("html", request.htmlBody())
                        .param("text", textBody)
                        .query(MAPPER)
                        .optional();
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "template_exists",
                        "A template with this name already exists");
            }
        });
        return updated.orElseThrow(() -> notFound());
    }

    public TemplatePreview preview(UUID tenantId, UUID id) {
        TemplateResponse template = get(tenantId, id);
        return new TemplatePreview(
                renderer.renderSubject(template.subject(), SAMPLE_VALUES),
                renderer.render(template.htmlBody(), SAMPLE_VALUES, true),
                renderer.render(template.textBody(), SAMPLE_VALUES, false));
    }

    private void validateVariables(TemplateRequest request) {
        Set<String> unknown = new TreeSet<>();
        unknown.addAll(renderer.variablesIn(request.subject()));
        unknown.addAll(renderer.variablesIn(request.htmlBody()));
        unknown.addAll(renderer.variablesIn(request.textBody()));
        unknown.removeAll(TemplateRenderer.ALLOWED_VARIABLES);
        if (!unknown.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_variable",
                    "Unknown template variables: " + String.join(", ", unknown));
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "template_not_found", "Template not found");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}