package io.casehub.platform.agent.gate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void burstAllowsImmediateConsumption() throws Exception {
        var bucket = new TokenBucket(1.0, 3);
        assertThat(bucket.tryAcquire(Duration.ZERO)).isTrue();
        assertThat(bucket.tryAcquire(Duration.ZERO)).isTrue();
        assertThat(bucket.tryAcquire(Duration.ZERO)).isTrue();
        assertThat(bucket.tryAcquire(Duration.ZERO)).isFalse();
    }

    @Test
    void refillAddsTokensOverTime() throws Exception {
        var bucket = new TokenBucket(10.0, 1);
        assertThat(bucket.tryAcquire(Duration.ZERO)).isTrue();
        assertThat(bucket.tryAcquire(Duration.ZERO)).isFalse();
        Thread.sleep(150);
        assertThat(bucket.tryAcquire(Duration.ZERO)).isTrue();
    }

    @Test
    void tryAcquireBlocksUntilTokenAvailable() throws Exception {
        var bucket = new TokenBucket(10.0, 1);
        bucket.tryAcquire(Duration.ZERO);
        long start = System.nanoTime();
        assertThat(bucket.tryAcquire(Duration.ofMillis(500))).isTrue();
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsed).isBetween(50L, 300L);
    }

    @Test
    void tryAcquireTimesOutWhenNoTokens() throws Exception {
        var bucket = new TokenBucket(0.5, 1);
        bucket.tryAcquire(Duration.ZERO);
        assertThat(bucket.tryAcquire(Duration.ofMillis(100))).isFalse();
    }

    @Test
    void releaseCreditsOneToken() throws Exception {
        var bucket = new TokenBucket(1.0, 2);
        bucket.tryAcquire(Duration.ZERO);
        bucket.tryAcquire(Duration.ZERO);
        assertThat(bucket.availablePermits()).isLessThan(1.0);
        bucket.release();
        assertThat(bucket.availablePermits()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void releaseCappedAtBurstCapacity() throws Exception {
        var bucket = new TokenBucket(1.0, 2);
        bucket.release();
        bucket.release();
        assertThat(bucket.availablePermits()).isLessThanOrEqualTo(2.0);
    }

    @Test
    void releaseWakesBlockedWaiter() throws Exception {
        var bucket = new TokenBucket(0.1, 1);
        bucket.tryAcquire(Duration.ZERO);
        var acquired = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                acquired.set(bucket.tryAcquire(Duration.ofSeconds(5)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latch.countDown();
        });
        Thread.sleep(50);
        bucket.release();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(acquired.get()).isTrue();
    }

    @Test
    void interruptedThreadGetsInterruptedException() throws Exception {
        var bucket = new TokenBucket(0.1, 1);
        bucket.tryAcquire(Duration.ZERO);
        var gotInterrupt = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);
        var thread = Thread.ofVirtual().start(() -> {
            try {
                bucket.tryAcquire(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                gotInterrupt.set(true);
            }
            latch.countDown();
        });
        Thread.sleep(50);
        thread.interrupt();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(gotInterrupt.get()).isTrue();
    }

    @Test
    void concurrentAccessRespectsThroughput() throws Exception {
        var bucket = new TokenBucket(20.0, 5);
        var acquired = new AtomicInteger();
        var done = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    if (bucket.tryAcquire(Duration.ofSeconds(2))) {
                        acquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                done.countDown();
            });
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(acquired.get()).isEqualTo(10);
    }
}
