package io.casehub.platform.agent.session;

import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class ManagedSession implements AgentSession {

    private final PooledSession pooledSession;
    private final String conversationId;
    private final SessionLifecycleManager manager;
    private final ClearingPolicy clearingPolicy;
    private final int clearAfterTurns;
    private final AtomicInteger turnCount = new AtomicInteger(0);

    ManagedSession(PooledSession pooledSession, String conversationId,
                   SessionLifecycleManager manager, ClearingPolicy clearingPolicy,
                   int clearAfterTurns) {
        this.pooledSession = pooledSession;
        this.conversationId = conversationId;
        this.manager = manager;
        this.clearingPolicy = clearingPolicy;
        this.clearAfterTurns = clearAfterTurns;
    }

    public String conversationId() { return conversationId; }
    public ClearingPolicy clearingPolicy() { return clearingPolicy; }
    public PooledSession pooledSession() { return pooledSession; }

    @Override
    public Multi<AgentEvent> query(String prompt) {
        int turn = turnCount.incrementAndGet();

        if (clearingPolicy == ClearingPolicy.EVERY_CALL && turn > 1) {
            pooledSession.delegate().clear();
        } else if (clearingPolicy == ClearingPolicy.AFTER_N_TURNS
                && clearAfterTurns > 0 && turn % clearAfterTurns == 0) {
            pooledSession.delegate().clear();
        }

        return pooledSession.delegate().query(prompt);
    }

    @Override
    public Uni<Void> interrupt() {
        return pooledSession.interrupt();
    }

    @Override
    public void clear() {
        pooledSession.delegate().clear();
        turnCount.set(0);
    }

    @Override
    public void close(Duration maxWait) {
        manager.release(conversationId);
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(30));
    }

    int turnCount() { return turnCount.get(); }
}
