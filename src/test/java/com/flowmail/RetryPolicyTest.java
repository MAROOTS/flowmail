package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmail.delivery.worker.RetryPolicy;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void firstRetryWaitsAboutTheBaseDelay() {
        RetryPolicy policy = new RetryPolicy(30, 900);
        for (int i = 0; i < 100; i++) {
            assertThat(policy.delaySeconds(1)).isBetween(24L, 36L);
        }
    }

    @Test
    void delayGrowsExponentiallyAndIsCapped() {
        RetryPolicy policy = new RetryPolicy(30, 900);
        for (int i = 0; i < 100; i++) {
            assertThat(policy.delaySeconds(2)).isBetween(48L, 72L);
            assertThat(policy.delaySeconds(10)).isBetween(720L, 900L);
        }
    }

    @Test
    void zeroBaseMeansNoDelay() {
        assertThat(new RetryPolicy(0, 0).delaySeconds(3)).isZero();
    }
}