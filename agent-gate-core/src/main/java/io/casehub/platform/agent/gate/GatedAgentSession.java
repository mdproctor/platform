package io.casehub.platform.agent.gate;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GatedAgentSession implements AgentSession {

    private final AgentSession delegate;
    private final List<AdmissionStrategy> sessionStrategies;
    private final List<AdmissionStrategy> queryStrategies;
    private final Duration queryAcquireTimeout;
    private final SessionRegistry registry;
    private final long sessionId;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public GatedAgentSession(AgentSession delegate,
                      List<AdmissionStrategy> sessionStrategies,
                      List<AdmissionStrategy> queryStrategies,
                      Duration queryAcquireTimeout,
                      SessionRegistry registry,
                      long sessionId) {
        this.delegate = delegate;
        this.sessionStrategies = sessionStrategies;
        this.queryStrategies = queryStrategies;
        this.queryAcquireTimeout = queryAcquireTimeout;
        this.registry = registry;
        this.sessionId = sessionId;
    }

    public long sessionId() {
        return sessionId;
    }

    @Override
    public Multi<AgentEvent> query(String prompt) {
        if (closed.get()) {
            return Multi.createFrom().failure(
                    new IllegalStateException("GatedAgentSession has been closed"));
        }
        if (!queryStrategies.isEmpty()) {
            AdmissionUtils.acquireAll(queryStrategies, queryAcquireTimeout);
        }
        return delegate.query(prompt);
    }

    @Override
    public Uni<Void> interrupt() {
        return delegate.interrupt();
    }

    @Override
    public void close(Duration maxWait) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            delegate.close(maxWait);
        } finally {
            registry.deregister(sessionId);
            AdmissionUtils.releaseAll(sessionStrategies);
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(30));
    }
}
