package io.casehub.platform.agent.gate;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class SlidingWindowStrategy implements AdmissionStrategy {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition slotAvailable = lock.newCondition();
    private final Deque<Long> timestamps = new ArrayDeque<>();
    private final int maxActions;
    private final long windowNanos;

    public SlidingWindowStrategy(int maxActions, Duration windowSize) {
        this.maxActions = maxActions;
        this.windowNanos = windowSize.toNanos();
    }

    @Override
    public Scope scope() {
        return Scope.INVOCATION;
    }

    @Override
    public boolean tryAcquire(Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        lock.lockInterruptibly();
        try {
            while (true) {
                pruneExpired();
                if (timestamps.size() < maxActions) {
                    timestamps.addLast(System.nanoTime());
                    return true;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                long oldestExpiry = timestamps.peekFirst() + windowNanos;
                long waitNanos = Math.min(oldestExpiry - System.nanoTime(), remainingNanos);
                if (waitNanos > 0) {
                    slotAvailable.await(waitNanos, TimeUnit.NANOSECONDS);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release() {
    }

    @Override
    public void rollback() {
        lock.lock();
        try {
            if (!timestamps.isEmpty()) {
                timestamps.pollLast();
                slotAvailable.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            timestamps.clear();
            slotAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void pruneExpired() {
        long cutoff = System.nanoTime() - windowNanos;
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }
    }
}
