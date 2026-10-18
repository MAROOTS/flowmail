package com.flowmail.delivery.worker;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Getter
@Component
@ConditionalOnProperty(name = "flowmail.worker.enabled", havingValue = "true")
public class WorkerSettings {

    private final int batchSize;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final long baseBackoffSeconds;
    private final long maxBackoffSeconds;
    private final String publicBaseUrl;

    public WorkerSettings(@Value("${flowmail.worker.batch-size:20}") int batchSize,
                          @Value("${flowmail.worker.lease-seconds:300}") int leaseSeconds,
                          @Value("${flowmail.worker.max-attempts:5}") int maxAttempts,
                          @Value("${flowmail.worker.base-backoff-seconds:30}") long baseBackoffSeconds,
                          @Value("${flowmail.worker.max-backoff-seconds:900}") long maxBackoffSeconds,
                          @Value("${flowmail.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
        this.baseBackoffSeconds = baseBackoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.publicBaseUrl = publicBaseUrl;
    }
}