package io.casehub.platform.agent.gate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class TokenBucket {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition tokensAvailable = lock.newCondition();
    private final double permitsPerSecond;
    private final double maxPermits;
    private double storedPermits;
    private long lastRefillNanos;

    TokenBucket(double permitsPerSecond, int burstCapacity) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be > 0");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("burstCapacity must be > 0");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.maxPermits = burstCapacity;
        this.storedPermits = burstCapacity;
        this.lastRefillNanos = System.nanoTime();
    }

    boolean tryAcquire(Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        lock.lockInterruptibly();
        try {
            while (true) {
                refill();
                if (storedPermits >= 1.0) {
                    storedPermits -= 1.0;
                    tokensAvailable.signal();
                    return true;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                double deficit = 1.0 - storedPermits;
                long waitNanos = (long) Math.ceil(
                        deficit / permitsPerSecond * 1_000_000_000L);
                long actualWait = Math.min(waitNanos, remainingNanos);
                tokensAvailable.await(actualWait, TimeUnit.NANOSECONDS);
            }
        } finally {
            lock.unlock();
        }
    }

    void release() {
        lock.lock();
        try {
            storedPermits = Math.min(maxPermits, storedPermits + 1.0);
            tokensAvailable.signal();
        } finally {
            lock.unlock();
        }
    }

    double availablePermits() {
        lock.lock();
        try {
            refill();
            return storedPermits;
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        long now = System.nanoTime();
        if (now > lastRefillNanos) {
            double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
            storedPermits = Math.min(maxPermits,
                    storedPermits + elapsed * permitsPerSecond);
            lastRefillNanos = now;
        }
    }
}
