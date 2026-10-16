package com.flowmail.tenancy;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work inside a transaction with the tenant context set for Postgres row-level security.
 * set_config(..., true) is transaction-local, so it cannot leak to the next user of a pooled connection.
 */
@Component
public class TenantTx {

    private final TransactionTemplate template;
    private final JdbcClient jdbc;

    public TenantTx(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        this.template = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    public <T> T call(UUID tenantId, Supplier<T> work) {
        Objects.requireNonNull(tenantId, "tenantId");
        return template.execute(status -> {
            jdbc.sql("select set_config('app.tenant_id', :tenant, true)")
                    .param("tenant", tenantId.toString())
                    .query(String.class)
                    .single();
            return work.get();
        });
    }

    public void run(UUID tenantId, Runnable work) {
        call(tenantId, () -> {
            work.run();
            return null;
        });
    }
}