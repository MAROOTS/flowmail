package com.flowmail.campaigns.service;

import com.flowmail.campaigns.UsageResponse;
import com.flowmail.common.ApiException;
import com.flowmail.tenancy.TenantTx;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UsageService {

    private final JdbcClient jdbc;
    private final TenantTx tenantTx;

    public static LocalDate currentPeriod() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    }

    /**
     * Reserves quota with one atomic statement. Must run inside a TenantTx transaction so that a
     * failure rolls back together with whatever the caller already did.
     * Concurrent reservations queue on the counter row; the loser re-checks the limit against
     * the winner's committed total and gets no row back.
     */
    public void reserve(UUID tenantId, int count) {
        LocalDate period = currentPeriod();

        jdbc.sql("""
                insert into usage_counters (tenant_id, period, used)
                values (:tenant, :period, 0)
                on conflict (tenant_id, period) do nothing
                """)
                .param("tenant", tenantId)
                .param("period", period)
                .update();

        Optional<Integer> used = jdbc.sql("""
                update usage_counters u
                set used = u.used + :count
                where u.tenant_id = :tenant
                  and u.period = :period
                  and u.used + :count <= (select t.monthly_email_limit from tenants t where t.id = :tenant)
                returning u.used
                """)
                .param("tenant", tenantId)
                .param("period", period)
                .param("count", count)
                .query(Integer.class)
                .optional();

        if (used.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "quota_exceeded",
                    "This send would exceed your monthly email limit");
        }
    }

    public UsageResponse current(UUID tenantId) {
        LocalDate period = currentPeriod();
        return tenantTx.call(tenantId, () -> {
            int limit = jdbc.sql("select monthly_email_limit from tenants where id = :tenant")
                    .param("tenant", tenantId)
                    .query(Integer.class)
                    .single();
            int used = jdbc.sql("""
                    select used from usage_counters
                    where tenant_id = :tenant and period = :period
                    """)
                    .param("tenant", tenantId)
                    .param("period", period)
                    .query(Integer.class)
                    .optional()
                    .orElse(0);
            return new UsageResponse(period, used, limit);
        });
    }
}