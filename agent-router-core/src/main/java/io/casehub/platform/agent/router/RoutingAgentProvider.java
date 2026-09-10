package io.casehub.platform.agent.router;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

public class RoutingAgentProvider implements AgentProvider {

    private static final Logger LOG = Logger.getLogger(RoutingAgentProvider.class);

    private final Map<String, AgentBackend> backends;
    private final AgentBackend defaultBackend;

    public RoutingAgentProvider(Iterable<AgentBackend> backends, String defaultKey) {
        this.backends = new HashMap<>();
        AgentBackend fallback = null;
        for (AgentBackend backend : backends) {
            this.backends.put(backend.key(), backend);
            if (backend.key().equals(defaultKey)) {
                fallback = backend;
            }
        }
        this.defaultBackend = fallback;
        LOG.infof("Agent router initialized: %d backend(s) [%s], default=%s",
                this.backends.size(), String.join(", ", this.backends.keySet()),
                defaultKey);
    }

    @Override
    public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        return resolve(config.model()).invoke(config);
    }

    @Override
    public AgentSession openSession(AgentSessionInit init) {
        return resolve(init.model()).openSession(init);
    }

    private AgentBackend resolve(String model) {
        if (model == null) {
            if (defaultBackend == null) {
                throw new IllegalStateException(
                        "No default backend configured — set casehub.platform.agent.default-backend");
            }
            return defaultBackend;
        }
        AgentBackend backend = backends.get(model);
        if (backend != null) return backend;
        AgentBackend catchAll = backends.get("langchain4j");
        if (catchAll != null) return catchAll;
        throw new IllegalArgumentException("No backend for key: " + model +
                ". Available: " + backends.keySet());
    }
}
