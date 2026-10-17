package com.flowmail.contacts.service;

import com.flowmail.common.ApiException;
import com.flowmail.tenancy.TenantTx;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UnsubscribeService {

    private final JdbcClient jdbc;
    private final TenantTx tenantTx;

    /** Idempotent. The tenant comes from a verified token, never from request input. */
    public void unsubscribe(UUID tenantId, UUID contactId) {
        tenantTx.run(tenantId, () -> {
            String email = jdbc.sql("select email from contacts where tenant_id = :tenant and id = :id")
                    .param("tenant", tenantId)
                    .param("id", contactId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "contact_not_found", "Contact not found"));

            jdbc.sql("""
                    update contacts
                    set status = 'unsubscribed', unsubscribed_at = now()
                    where tenant_id = :tenant and id = :id and status = 'subscribed'
                    """)
                    .param("tenant", tenantId)
                    .param("id", contactId)
                    .update();

            jdbc.sql("""
                    insert into suppression_entries (tenant_id, email, reason, source)
                    values (:tenant, :email, 'unsubscribe', 'link')
                    on conflict (tenant_id, email) do nothing
                    """)
                    .param("tenant", tenantId)
                    .param("email", email)
                    .update();
        });
    }
}