package io.casehub.platform.agent.gate;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowStrategyTest {

    @Test
    void scopeIsInvocation() {
        var strategy = new SlidingWindowStrategy(5, Duration.ofSeconds(60));
        assertThat(strategy.scope()).isEqualTo(AdmissionStrategy.Scope.INVOCATION);
    }

    @Test
    void admitsWithinLimit() throws Exception {
        var strategy = new SlidingWindowStrategy(3, Duration.ofSeconds(60));
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void rejectsWhenLimitReached() throws Exception {
        var strategy = new SlidingWindowStrategy(2, Duration.ofSeconds(60));
        strategy.tryAcquire(Duration.ofSeconds(1));
        strategy.tryAcquire(Duration.ofSeconds(1));
        assertThat(strategy.tryAcquire(Duration.ofMillis(50))).isFalse();
    }

    @Test
    void rollbackRemovesTimestamp() throws Exception {
        var strategy = new SlidingWindowStrategy(1, Duration.ofSeconds(60));
        strategy.tryAcquire(Duration.ofSeconds(1));
        strategy.rollback();
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void releaseIsNoOp() throws Exception {
        var strategy = new SlidingWindowStrategy(1, Duration.ofSeconds(60));
        strategy.tryAcquire(Duration.ofSeconds(1));
        strategy.release();
        assertThat(strategy.tryAcquire(Duration.ofMillis(50))).isFalse();
    }

    @Test
    void clearResetsAllState() throws Exception {
        var strategy = new SlidingWindowStrategy(1, Duration.ofSeconds(60));
        strategy.tryAcquire(Duration.ofSeconds(1));
        assertThat(strategy.tryAcquire(Duration.ofMillis(50))).isFalse();
        strategy.clear();
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void expiredTimestampsArePruned() throws Exception {
        var strategy = new SlidingWindowStrategy(1, Duration.ofMillis(100));
        strategy.tryAcquire(Duration.ofSeconds(1));
        assertThat(strategy.tryAcquire(Duration.ofMillis(10))).isFalse();
        Thread.sleep(150);
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
    }
}
