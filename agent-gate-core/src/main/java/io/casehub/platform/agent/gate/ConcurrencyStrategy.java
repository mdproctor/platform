package io.casehub.platform.agent.gate;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class ConcurrencyStrategy implements AdmissionStrategy {

    private final Semaphore gate;

    public ConcurrencyStrategy(int maxConcurrent) {
        this.gate = new Semaphore(maxConcurrent, true);
    }

    @Override
    public Scope scope() {
        return Scope.SESSION;
    }

    @Override
    public boolean tryAcquire(Duration timeout) throws InterruptedException {
        return gate.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void release() {
        gate.release();
    }

    @Override
    public void rollback() {
        gate.release();
    }
}
