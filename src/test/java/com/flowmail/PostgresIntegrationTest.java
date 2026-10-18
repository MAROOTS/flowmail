package com.flowmail;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgresIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "flowmail_app");
        registry.add("spring.datasource.password", () -> "flowmail_app");

        registry.add("flowmail.worker.enabled", () -> "true");
        registry.add("flowmail.worker.polling", () -> "false");
        registry.add("flowmail.worker.db.url", POSTGRES::getJdbcUrl);
        registry.add("flowmail.worker.db.user", () -> "flowmail_worker");
        registry.add("flowmail.worker.db.password", () -> "flowmail_worker");
        registry.add("flowmail.worker.base-backoff-seconds", () -> "0");
        registry.add("flowmail.worker.max-backoff-seconds", () -> "0");
        registry.add("flowmail.mail.provider", () -> "mock");
    }
}