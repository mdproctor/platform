package io.casehub.platform.agent.session;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.config.PoolDeclaration;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SessionPoolRegistry {

    private static final Logger LOG = Logger.getLogger(SessionPoolRegistry.class);

    private final Map<String, SessionPool> pools = new ConcurrentHashMap<>();

    public SessionPoolRegistry(List<PoolDeclaration> declarations,
                                BackendInstanceRegistry backends) {
        for (PoolDeclaration decl : declarations) {
            var config = SessionPoolConfig.fromDeclaration(decl);
            Optional<AgentBackend> backend = backends.resolve(config.backendKey(), "default");
            if (backend.isPresent()) {
                var pool = new SessionPool(backend.get(), config);
                pools.put(poolKey(config.backendKey(), null), pool);
                LOG.infof("Session pool created: %s (min=%d, max=%d, backend=%s)",
                        config.name(), config.minActive(), config.maxActive(), config.backendKey());
            } else {
                LOG.warnf("Pool '%s' references unknown backend '%s' — skipped",
                        decl.name(), config.backendKey());
            }
        }
    }

    public Optional<SessionPool> findPool(String backendKey, String model) {
        return Optional.ofNullable(pools.get(poolKey(backendKey, model)))
                .or(() -> Optional.ofNullable(pools.get(poolKey(backendKey, null))));
    }

    public void shutdown() {
        pools.values().forEach(SessionPool::shutdown);
    }

    private static String poolKey(String backendKey, String model) {
        return model != null ? backendKey + ":" + model : backendKey;
    }
}
