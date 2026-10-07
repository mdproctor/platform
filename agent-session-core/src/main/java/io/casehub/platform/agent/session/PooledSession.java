package io.casehub.platform.agent.session;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class PooledSession implements AgentSession {

    private final AgentSession delegate;
    private final SessionPool pool;
    private final String backendKey;
    private final Instant checkedOutAt;
    private final AtomicBoolean released = new AtomicBoolean(false);

    PooledSession(AgentSession delegate, SessionPool pool, String backendKey) {
        this.delegate = Objects.requireNonNull(delegate);
        this.pool = Objects.requireNonNull(pool);
        this.backendKey = backendKey;
        this.checkedOutAt = Instant.now();
    }

    public AgentSession delegate() { return delegate; }
    public String backendKey() { return backendKey; }
    public Instant checkedOutAt() { return checkedOutAt; }

    @Override
    public Multi<AgentEvent> query(String prompt) {
        return delegate.query(prompt);
    }

    @Override
    public Uni<Void> interrupt() {
        return delegate.interrupt();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public void close(Duration maxWait) {
        if (released.compareAndSet(false, true)) {
            pool.release(this);
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(30));
    }

    void destroyDelegate(Duration maxWait) {
        delegate.close(maxWait);
    }

    void destroyDelegate() {
        delegate.close();
    }
}
