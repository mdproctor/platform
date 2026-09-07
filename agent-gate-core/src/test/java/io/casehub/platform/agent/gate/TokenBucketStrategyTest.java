package io.casehub.platform.agent.gate;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketStrategyTest {

    @Test
    void scopeIsInvocation() {
        var strategy = new TokenBucketStrategy(1.0, 1);
        assertThat(strategy.scope()).isEqualTo(AdmissionStrategy.Scope.INVOCATION);
    }

    @Test
    void acquireConsumesToken() throws Exception {
        var strategy = new TokenBucketStrategy(1.0, 1);
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
        assertThat(strategy.tryAcquire(Duration.ofMillis(50))).isFalse();
    }

    @Test
    void releaseIsNoOp() throws Exception {
        var strategy = new TokenBucketStrategy(1.0, 1);
        strategy.tryAcquire(Duration.ofSeconds(1));
        strategy.release();
        assertThat(strategy.tryAcquire(Duration.ofMillis(50))).isFalse();
    }

    @Test
    void rollbackReturnsToken() throws Exception {
        var strategy = new TokenBucketStrategy(1.0, 1);
        strategy.tryAcquire(Duration.ofSeconds(1));
        strategy.rollback();
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void burstAllowsMultipleAcquisitions() throws Exception {
        var strategy = new TokenBucketStrategy(1.0, 3);
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
        assertThat(strategy.tryAcquire(Duration.ofMillis(50))).isFalse();
    }
}
