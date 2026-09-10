package io.casehub.platform.agent.gate;

import java.time.Duration;

public interface AdmissionStrategy {

    enum Scope { INVOCATION, SESSION }

    Scope scope();

    boolean tryAcquire(Duration timeout) throws InterruptedException;

    void release();

    void rollback();
}
