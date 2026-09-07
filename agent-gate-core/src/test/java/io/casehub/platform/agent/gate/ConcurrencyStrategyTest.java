package io.casehub.platform.agent.gate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyStrategyTest {

    @Test
    void scopeIsSession() {
        var strategy = new ConcurrencyStrategy(1);
        assertThat(strategy.scope()).isEqualTo(AdmissionStrategy.Scope.SESSION);
    }

    @Test
    void acquireAndRelease() throws Exception {
        var strategy = new ConcurrencyStrategy(1);
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
        strategy.release();
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void acquireAndRollback() throws Exception {
        var strategy = new ConcurrencyStrategy(1);
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
        strategy.rollback();
        assertThat(strategy.tryAcquire(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void timeoutWhenAllPermitsHeld() throws Exception {
        var strategy = new ConcurrencyStrategy(1);
        strategy.tryAcquire(Duration.ofSeconds(1));
        assertThat(strategy.tryAcquire(Duration.ofMillis(50))).isFalse();
    }

    @Test
    void blocksUntilPermitReleased() throws Exception {
        var strategy = new ConcurrencyStrategy(1);
        strategy.tryAcquire(Duration.ofSeconds(1));
        var acquired = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                acquired.set(strategy.tryAcquire(Duration.ofSeconds(5)));
            } catch (InterruptedException ignored) {}
            latch.countDown();
        });
        Thread.sleep(50);
        strategy.release();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(acquired.get()).isTrue();
    }
}
