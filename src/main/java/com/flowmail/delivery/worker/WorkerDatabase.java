package com.flowmail.delivery.worker;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "flowmail.worker.enabled", havingValue = "true")
public class WorkerDatabase {

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public WorkerDatabase(@Value("${flowmail.worker.db.url}") String url,
                          @Value("${flowmail.worker.db.user}") String user,
                          @Value("${flowmail.worker.db.password}") String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setPoolName("flowmail-worker");
        this.dataSource = new HikariDataSource(config);
        this.jdbc = JdbcClient.create(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public TransactionTemplate tx() {
        return tx;
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}