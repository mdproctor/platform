package io.casehub.platform.agent.router;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelRegistry;
import io.smallrye.mutiny.Multi;
import org.jboss.logging.Logger;

import java.util.Optional;

public class RoutingAgentProvider implements AgentProvider {

    private static final Logger LOG = Logger.getLogger(RoutingAgentProvider.class);

    private final BackendInstanceRegistry registry;
    private final String defaultBackendKey;
    private final ModelRegistry modelRegistry;

    private record ResolvedRoute(AgentBackend backend, String apiModelId) {}

    public RoutingAgentProvider(BackendInstanceRegistry registry,
                                String defaultBackendKey,
                                ModelRegistry modelRegistry) {
        this.registry          = registry;
        this.defaultBackendKey = defaultBackendKey;
        this.modelRegistry     = modelRegistry;
        LOG.infof("Agent router initialized with registry, default=%s", defaultBackendKey);
    }

    @Override
    public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        var route = resolve(config.model());
        var rewritten = new AgentSessionConfig(
                config.systemPrompt(), config.userPrompt(), config.mcpServers(),
                config.timeout(), config.correlationId(), route.apiModelId());
        return route.backend().invoke(rewritten);
    }

    @Override
    public AgentSession openSession(AgentSessionInit init) {
        var route = resolve(init.model());
        var rewritten = new AgentSessionInit(
                init.systemPrompt(), init.mcpServers(),
                init.timeout(), init.correlationId(), route.apiModelId());
        return route.backend().openSession(rewritten);
    }

    private ResolvedRoute resolve(String model) {
        if (model == null) {
            var backend = registry.resolve(defaultBackendKey, "default");
            if (backend.isEmpty()) {
                throw new IllegalStateException(
                        "No default backend configured: " + defaultBackendKey);
            }
            return new ResolvedRoute(backend.get(), null);
        }

        Optional<ModelDescriptor> descriptor = modelRegistry.resolveById(model);
        if (descriptor.isPresent()) {
            var    d          = descriptor.get();
            String instanceId = d.backendInstanceId() != null ? d.backendInstanceId() : "default";
            var    backend    = registry.resolve(d.backendKey(), instanceId);
            if (backend.isEmpty()) {
                throw new IllegalStateException(
                        "Model '" + model + "' resolved to backend " + d.backendKey()
                        + "/" + instanceId + ", but no backend with that key/instance is registered");
            }
            return new ResolvedRoute(backend.get(), d.apiModelId());
        }

        var backend = registry.resolve(model, "default");
        if (backend.isPresent()) {
            return new ResolvedRoute(backend.get(), null);
        }

        throw new IllegalArgumentException("No model or backend for: " + model);}
}
