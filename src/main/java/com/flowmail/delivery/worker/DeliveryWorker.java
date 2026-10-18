package com.flowmail.delivery.worker;

import com.flowmail.campaigns.service.TemplateRenderer;
import com.flowmail.contacts.service.UnsubscribeTokenService;
import com.flowmail.delivery.provider.EmailProvider;
import com.flowmail.delivery.provider.OutboundEmail;
import com.flowmail.delivery.provider.PermanentSendException;
import com.flowmail.delivery.provider.SendResult;
import com.flowmail.delivery.provider.TransientSendException;
import com.flowmail.delivery.provider.UncertainSendException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@ConditionalOnProperty(name = "flowmail.worker.enabled", havingValue = "true")
public class DeliveryWorker {

    private static final String CLAIM_SQL = """
            select m.id, m.status, m.attempt_count
            from email_messages m
            where (m.status = 'queued' and m.next_attempt_at <= now())
               or (m.status = 'sending' and m.locked_until < now())
            order by m.next_attempt_at, m.id
            limit :limit
            for update skip locked
            """;

    private static final String LEASE_SQL = """
            update email_messages
            set status = 'sending',
                attempt_count = attempt_count + 1,
                locked_until = now() + :lease * interval '1 second',
                locked_by = :worker,
                updated_at = now()
            where id in (:ids)
            """;

    private static final String CAMPAIGN_START_SQL = """
            update campaigns
            set status = 'sending', updated_at = now()
            where status = 'queued'
              and id in (select campaign_id from email_messages where id in (:ids))
            """;

    private static final String DETAILS_SQL = """
            select m.id, m.tenant_id, m.campaign_id, m.contact_id, m.email, m.attempt_count,
                   c.from_name, c.from_email, c.snapshot_subject, c.snapshot_html, c.snapshot_text,
                   ct.first_name, ct.last_name, ct.status as contact_status,
                   exists (select 1 from suppression_entries s
                           where s.tenant_id = m.tenant_id and s.email = m.email) as suppressed
            from email_messages m
            join campaigns c on c.tenant_id = m.tenant_id and c.id = m.campaign_id
            join contacts ct on ct.tenant_id = m.tenant_id and ct.id = m.contact_id
            where m.id in (:ids)
            """;

    // The attempt_count in the WHERE clause is the fencing token: a worker that lost its lease
    // cannot overwrite the result of a newer attempt.
    private static final String FINISH_SQL = """
            update email_messages
            set status = cast(:status as text),
                next_attempt_at = case when cast(:status as text) = 'queued'
                                       then now() + cast(:delay as bigint) * interval '1 second'
                                       else next_attempt_at end,
                provider_message_id = coalesce(cast(:provider as text), provider_message_id),
                sent_at = case when cast(:status as text) = 'sent' then now() else sent_at end,
                last_error = cast(:error as text),
                locked_until = null,
                locked_by = null,
                updated_at = now()
            where id = :id and status = 'sending' and attempt_count = :attempt
            """;

    private static final String ATTEMPT_SQL = """
            insert into delivery_attempts (tenant_id, message_id, attempt_number, outcome, error, provider_message_id)
            select tenant_id, id, :attempt, :outcome, cast(:error as text), cast(:provider as text)
            from email_messages
            where id = :id
            """;

    private static final String COMPLETE_CAMPAIGNS_SQL = """
            update campaigns c
            set status = 'completed', updated_at = now()
            where c.status = 'sending'
              and not exists (
                  select 1 from email_messages m
                  where m.tenant_id = c.tenant_id and m.campaign_id = c.id
                    and m.status in ('queued', 'sending'))
            """;

    private static final RowMapper<Claimed> CLAIMED_MAPPER = (rs, i) -> new Claimed(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("contact_id", UUID.class),
            rs.getString("email"),
            rs.getInt("attempt_count"),
            rs.getString("from_name"),
            rs.getString("from_email"),
            rs.getString("snapshot_subject"),
            rs.getString("snapshot_html"),
            rs.getString("snapshot_text"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("contact_status"),
            rs.getBoolean("suppressed"));

    private record DueRow(UUID id, String status, int attemptCount) {
    }

    private record Claimed(UUID messageId, UUID tenantId, UUID contactId, String email, int attempt,
                           String fromName, String fromEmail, String subject, String html, String text,
                           String firstName, String lastName, String contactStatus, boolean suppressed) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final WorkerSettings settings;
    private final EmailProvider provider;
    private final TemplateRenderer renderer;
    private final UnsubscribeTokenService tokens;
    private final RetryPolicy retryPolicy;
    private final String workerId = "worker-" + UUID.randomUUID();

    public DeliveryWorker(WorkerDatabase database, WorkerSettings settings, EmailProvider provider,
                          TemplateRenderer renderer, UnsubscribeTokenService tokens) {
        this.jdbc = database.jdbc();
        this.tx = database.tx();
        this.settings = settings;
        this.provider = provider;
        this.renderer = renderer;
        this.tokens = tokens;
        this.retryPolicy = new RetryPolicy(settings.getBaseBackoffSeconds(), settings.getMaxBackoffSeconds());
    }

    /** One polling pass. Returns the number of messages it attempted, so callers can loop until idle. */
    public int runOnce() {
        List<Claimed> batch = claim();
        for (Claimed message : batch) {
            process(message);
        }
        completeFinishedCampaigns();
        return batch.size();
    }

    private List<Claimed> claim() {
        List<Claimed> claimed = tx.execute(status -> {
            List<DueRow> due = jdbc.sql(CLAIM_SQL)
                    .param("limit", settings.getBatchSize())
                    .query((rs, i) -> new DueRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("status"),
                            rs.getInt("attempt_count")))
                    .list();

            List<UUID> toSend = new ArrayList<>();
            for (DueRow row : due) {
                if ("sending".equals(row.status()) && !provider.supportsIdempotencyKeys()) {
                    // The previous worker may have handed this to the provider before dying.
                    // Re-sending could deliver twice, so flag it instead.
                    markUncertainAfterCrash(row);
                } else {
                    toSend.add(row.id());
                }
            }
            if (toSend.isEmpty()) {
                return new ArrayList<Claimed>();
            }

            jdbc.sql(LEASE_SQL)
                    .param("lease", settings.getLeaseSeconds())
                    .param("worker", workerId)
                    .param("ids", toSend)
                    .update();
            jdbc.sql(CAMPAIGN_START_SQL).param("ids", toSend).update();

            return jdbc.sql(DETAILS_SQL).param("ids", toSend).query(CLAIMED_MAPPER).list();
        });
        return claimed == null ? List.of() : claimed;
    }

    private void markUncertainAfterCrash(DueRow row) {
        String error = "Worker lost while sending; delivery is uncertain and was not retried";
        jdbc.sql("""
                update email_messages
                set status = 'unknown', last_error = :error, locked_until = null, locked_by = null,
                    updated_at = now()
                where id = :id
                """)
                .param("error", error)
                .param("id", row.id())
                .update();
        jdbc.sql(ATTEMPT_SQL)
                .param("attempt", row.attemptCount())
                .param("outcome", "uncertain")
                .param("error", error)
                .param("provider", null)
                .param("id", row.id())
                .update();
    }

    private void process(Claimed message) {
        if (!"subscribed".equals(message.contactStatus()) || message.suppressed()) {
            finish(message, "canceled", 0, "Recipient is no longer eligible", null, "dropped");
            return;
        }

        OutboundEmail email;
        try {
            email = build(message);
        } catch (RuntimeException e) {
            finish(message, "failed", 0, "Rendering failed: " + e.getClass().getSimpleName(), null,
                    "permanent_failure");
            return;
        }

        try {
            SendResult result = provider.send(email);
            finish(message, "sent", 0, null, result.providerMessageId(), "sent");
        } catch (PermanentSendException e) {
            finish(message, "failed", 0, truncate(e.getMessage()), null, "permanent_failure");
        } catch (UncertainSendException e) {
            finish(message, "unknown", 0, truncate(e.getMessage()), null, "uncertain");
        } catch (TransientSendException e) {
            retryOrFail(message, truncate(e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Unexpected error sending message {}", message.messageId(), e);
            retryOrFail(message, "Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    private void retryOrFail(Claimed message, String error) {
        if (message.attempt() >= settings.getMaxAttempts()) {
            finish(message, "failed", 0,
                    "Gave up after " + message.attempt() + " attempts: " + error, null, "transient_failure");
        } else {
            finish(message, "queued", retryPolicy.delaySeconds(message.attempt()), error, null,
                    "transient_failure");
        }
    }

    private OutboundEmail build(Claimed message) {
        String unsubscribeUrl = settings.getPublicBaseUrl() + "/unsubscribe/"
                + tokens.create(message.tenantId(), message.contactId());
        Map<String, String> values = Map.of(
                "firstName", nullToEmpty(message.firstName()),
                "lastName", nullToEmpty(message.lastName()),
                "email", message.email(),
                "unsubscribeUrl", unsubscribeUrl);

        return new OutboundEmail(
                message.messageId().toString(),
                message.fromName(),
                message.fromEmail(),
                message.email(),
                renderer.renderSubject(message.subject(), values),
                renderer.render(message.html(), values, true),
                renderer.render(message.text(), values, false),
                unsubscribeUrl);
    }

    private void finish(Claimed message, String status, long delaySeconds, String error,
                        String providerMessageId, String outcome) {
        tx.executeWithoutResult(txStatus -> {
            int updated = jdbc.sql(FINISH_SQL)
                    .param("status", status)
                    .param("delay", delaySeconds)
                    .param("provider", providerMessageId)
                    .param("error", error)
                    .param("id", message.messageId())
                    .param("attempt", message.attempt())
                    .update();
            if (updated == 0) {
                log.warn("Message {} attempt {} is no longer owned by this worker; result ignored",
                        message.messageId(), message.attempt());
                return;
            }
            jdbc.sql(ATTEMPT_SQL)
                    .param("attempt", message.attempt())
                    .param("outcome", outcome)
                    .param("error", error)
                    .param("provider", providerMessageId)
                    .param("id", message.messageId())
                    .update();
        });
    }

    private void completeFinishedCampaigns() {
        tx.executeWithoutResult(status -> jdbc.sql(COMPLETE_CAMPAIGNS_SQL).update());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}