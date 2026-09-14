package io.casehub.platform.agent.router.quarkus;

import io.casehub.platform.agent.BackendInstanceRegistry;
import io.casehub.platform.agent.router.RoutingAgentProvider;
import io.casehub.platform.agent.router.config.RoutingAgentConfig;
import io.casehub.platform.api.model.ModelRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class RouterBeans {

    @Inject BackendInstanceRegistry registry;
    @Inject ModelRegistry modelRegistry;

    @Produces
    @ApplicationScoped
    public RoutingAgentProvider routingAgentProvider(RoutingAgentConfig config) {
        return new RoutingAgentProvider(registry, config.defaultBackend(), modelRegistry);
    }
}
