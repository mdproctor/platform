package io.casehub.platform.agent.gate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyCompositionTest {

    @Test
    void rollbackPriorStrategiesWhenLaterFails() throws Exception {
        var tokenBucket = new TokenBucketStrategy(1.0, 1);
        var concurrency = new ConcurrencyStrategy(0);

        var strategies = List.<AdmissionStrategy>of(tokenBucket, concurrency);
        boolean acquired = acquireAll(strategies, Duration.ofMillis(50));

        assertThat(acquired).isFalse();
        assertThat(tokenBucket.tryAcquire(Duration.ofMillis(50))).isTrue();
    }

    @Test
    void acquireAllSucceedsWhenAllStrategiesSucceed() throws Exception {
        var tokenBucket = new TokenBucketStrategy(10.0, 10);
        var concurrency = new ConcurrencyStrategy(5);

        var strategies = List.<AdmissionStrategy>of(tokenBucket, concurrency);
        boolean acquired = acquireAll(strategies, Duration.ofSeconds(1));

        assertThat(acquired).isTrue();
    }

    @Test
    void acquisitionOrderIsPreserved() throws Exception {
        var order = new ArrayList<String>();
        var s1 = new TrackingStrategy("first", order, true);
        var s2 = new TrackingStrategy("second", order, true);

        acquireAll(List.of(s1, s2), Duration.ofSeconds(1));

        assertThat(order).containsExactly("first:acquire", "second:acquire");
    }

    @Test
    void rollbackHappensInReverseOrder() throws Exception {
        var order = new ArrayList<String>();
        var s1 = new TrackingStrategy("first", order, true);
        var s2 = new TrackingStrategy("second", order, true);
        var s3 = new TrackingStrategy("third", order, false);

        acquireAll(List.of(s1, s2, s3), Duration.ofSeconds(1));

        assertThat(order).containsExactly(
                "first:acquire", "second:acquire", "third:acquire",
                "second:rollback", "first:rollback");
    }

    private static boolean acquireAll(List<AdmissionStrategy> strategies,
                                       Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        for (int i = 0; i < strategies.size(); i++) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            Duration remaining = remainingNanos > 0 ? Duration.ofNanos(remainingNanos) : Duration.ZERO;
            if (!strategies.get(i).tryAcquire(remaining)) {
                for (int j = i - 1; j >= 0; j--) {
                    strategies.get(j).rollback();
                }
                return false;
            }
        }
        return true;
    }

    private record TrackingStrategy(String name, List<String> log,
                                     boolean shouldSucceed) implements AdmissionStrategy {
        @Override public Scope scope() { return Scope.INVOCATION; }
        @Override public boolean tryAcquire(Duration timeout) {
            log.add(name + ":acquire");
            return shouldSucceed;
        }
        @Override public void release() { log.add(name + ":release"); }
        @Override public void rollback() { log.add(name + ":rollback"); }
    }
}
