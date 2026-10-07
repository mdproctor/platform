package io.casehub.platform.agent.session;

import io.casehub.platform.agent.AgentSessionInit;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

public class SessionLifecycleManager {

    private static final Logger LOG = Logger.getLogger(SessionLifecycleManager.class);

    private final SessionPoolRegistry                       poolRegistry;
    private final ConcurrentHashMap<String, ManagedSession> sessions = new ConcurrentHashMap<>();

    public SessionLifecycleManager(SessionPoolRegistry poolRegistry) {
        this.poolRegistry = poolRegistry;
    }

    public ManagedSession acquire(String conversationId, String backendKey, AgentSessionInit init) {
        return acquire(conversationId, backendKey, init, ClearingPolicy.MANUAL, 0);
    }

    public ManagedSession acquire(String conversationId, String backendKey, AgentSessionInit init,
                                  ClearingPolicy clearingPolicy, int clearAfterTurns) {
        return sessions.computeIfAbsent(conversationId, id -> {
            var pool = poolRegistry.findPool(backendKey, init.model())
                                   .orElseThrow(() -> new IllegalStateException(
                                           "No session pool for backend '" + backendKey + "'"));
            PooledSession pooled = pool.checkout(init);
            LOG.infof("Acquired managed session [conversationId=%s, backend=%s, policy=%s]",
                      conversationId, backendKey, clearingPolicy);
            return new ManagedSession(pooled, conversationId, this, clearingPolicy, clearAfterTurns);
        });
    }

    public void release(String conversationId) {
        ManagedSession session = sessions.remove(conversationId);
        if (session != null) {
            session.pooledSession().close();
            LOG.infof("Released managed session [conversationId=%s]", conversationId);
        }
    }

    public void close(String conversationId) {
        ManagedSession session = sessions.remove(conversationId);
        if (session != null) {
            session.pooledSession().destroyDelegate();
            LOG.infof("Closed managed session [conversationId=%s]", conversationId);
        }
    }

    public boolean hasSession(String conversationId) {
        return sessions.containsKey(conversationId);
    }

    public void shutdown() {
        sessions.forEach((id, session) -> {
            try {
                session.pooledSession().destroyDelegate();
            } catch (Exception e) {
                LOG.debugf("Failed to close managed session on shutdown [conversationId=%s]: %s",
                           id, e.getMessage());
            }
        });
        sessions.clear();
    }

    int activeCount() {return sessions.size();}
}
