package com.flowmail.delivery.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "flowmail.worker", name = {"enabled", "polling"}, havingValue = "true")
public class WorkerScheduler {

    private final DeliveryWorker worker;

    public WorkerScheduler(DeliveryWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${flowmail.worker.poll-interval-ms:1000}")
    void poll() {
        try {
            int attempted;
            do {
                attempted = worker.runOnce();
            } while (attempted > 0);
        } catch (Exception e) {
            log.error("Delivery pass failed", e);
        }
    }
}