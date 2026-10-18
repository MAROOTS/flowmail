package com.flowmail.delivery.provider;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "flowmail.mail.provider", havingValue = "mock")
public class MockEmailProvider implements EmailProvider {

    /** Simulates the worker process dying right after the provider accepted the email. */
    public static class SimulatedCrash extends Error {

        public SimulatedCrash(String message) {
            super(message);
        }
    }

    private final Map<String, String> providerIdByKey = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> transientFailures = new ConcurrentHashMap<>();
    private final Set<String> permanentFailures = ConcurrentHashMap.newKeySet();
    private final Set<String> uncertainFailures = ConcurrentHashMap.newKeySet();
    private final Set<String> crashAfterSend = ConcurrentHashMap.newKeySet();
    private volatile boolean idempotent = true;
    @Setter
    private volatile long latencyMillis = 0;

    @Override
    public boolean supportsIdempotencyKeys() {
        return idempotent;
    }

    @Override
    public SendResult send(OutboundEmail email) {
        if (latencyMillis > 0) {
            try {
                Thread.sleep(latencyMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (idempotent) {
            String existing = providerIdByKey.get(email.idempotencyKey());
            if (existing != null) {
                return new SendResult(existing);
            }
        }

        String to = email.to();
        if (permanentFailures.contains(to)) {
            throw new PermanentSendException("550 mailbox does not exist");
        }
        if (uncertainFailures.contains(to)) {
            throw new UncertainSendException("connection timed out after sending");
        }
        AtomicInteger remaining = transientFailures.get(to);
        if (remaining != null && remaining.getAndDecrement() > 0) {
            throw new TransientSendException("451 temporary failure, try again later");
        }

        String providerId = "mock-" + UUID.randomUUID();
        providerIdByKey.put(email.idempotencyKey(), providerId);
        deliveries.computeIfAbsent(to, k -> new AtomicInteger()).incrementAndGet();

        if (crashAfterSend.remove(to)) {
            throw new SimulatedCrash("worker died after the provider accepted " + to);
        }
        return new SendResult(providerId);
    }

    public void failTransiently(String email, int times) {
        transientFailures.put(email, new AtomicInteger(times));
    }

    public void failPermanently(String email) {
        permanentFailures.add(email);
    }

    public void failUncertain(String email) {
        uncertainFailures.add(email);
    }

    public void crashAfterSend(String email) {
        crashAfterSend.add(email);
    }

    public void setSupportsIdempotencyKeys(boolean value) {
        this.idempotent = value;
    }

    public int deliveriesTo(String email) {
        AtomicInteger count = deliveries.get(email);
        return count == null ? 0 : count.get();
    }

    public void reset() {
        providerIdByKey.clear();
        deliveries.clear();
        transientFailures.clear();
        permanentFailures.clear();
        uncertainFailures.clear();
        crashAfterSend.clear();
        idempotent = true;
        latencyMillis = 0;
    }
}