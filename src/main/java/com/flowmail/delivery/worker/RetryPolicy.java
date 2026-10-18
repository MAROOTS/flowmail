package com.flowmail.delivery.worker;

import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with +/-20% jitter, capped. A base of 0 disables the delay (used in tests). */
public class RetryPolicy {

    private final long baseSeconds;
    private final long maxSeconds;

    public RetryPolicy(long baseSeconds, long maxSeconds) {
        this.baseSeconds = baseSeconds;
        this.maxSeconds = maxSeconds;
    }

    public long delaySeconds(int attempt) {
        if (baseSeconds <= 0) {
            return 0;
        }
        double exponential = baseSeconds * Math.pow(2, Math.max(attempt - 1, 0));
        double capped = Math.min(exponential, maxSeconds);
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Math.max(1, Math.round(Math.min(capped * jitter, maxSeconds)));
    }
}