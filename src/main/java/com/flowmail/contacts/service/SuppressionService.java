package com.flowmail.contacts.service;

import com.flowmail.common.ApiException;
import com.flowmail.contacts.SuppressionResponse;
import com.flowmail.tenancy.TenantTx;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SuppressionService {

    private static final int MAX_PAGE_SIZE = 100;

    private static final RowMapper<SuppressionResponse> MAPPER = (rs, i) -> new SuppressionResponse(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("reason"),
            rs.getString("source"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final TenantTx tenantTx;

    public List<SuppressionResponse> list(UUID tenantId, int limit, int offset) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        int start = Math.max(offset, 0);

        return tenantTx.call(tenantId, () -> jdbc.sql("""
                select id, email, reason, source, created_at
                from suppression_entries
                where tenant_id = :tenant
                order by created_at, id
                limit :limit offset :offset
                """)
                .param("tenant", tenantId)
                .param("limit", pageSize)
                .param("offset", start)
                .query(MAPPER)
                .list());
    }

    public SuppressionResponse addManual(UUID tenantId, String rawEmail) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);

        return tenantTx.call(tenantId, () -> {
            try {
                return jdbc.sql("""
                        insert into suppression_entries (tenant_id, email, reason, source)
                        values (:tenant, :email, 'manual', 'dashboard')
                        returning id, email, reason, source, created_at
                        """)
                        .param("tenant", tenantId)
                        .param("email", email)
                        .query(MAPPER)
                        .single();
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "already_suppressed",
                        "This address is already on the suppression list");
            }
        });
    }

    public void removeManual(UUID tenantId, UUID id) {
        tenantTx.run(tenantId, () -> {
            String reason = jdbc.sql("""
                    select reason from suppression_entries
                    where tenant_id = :tenant and id = :id
                    """)
                    .param("tenant", tenantId)
                    .param("id", id)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "suppression_not_found", "Suppression entry not found"));

            if (!"manual".equals(reason)) {
                throw new ApiException(HttpStatus.CONFLICT, "suppression_not_removable",
                        "Unsubscribes, bounces and complaints cannot be removed");
            }

            jdbc.sql("delete from suppression_entries where tenant_id = :tenant and id = :id")
                    .param("tenant", tenantId)
                    .param("id", id)
                    .update();
        });
    }
}