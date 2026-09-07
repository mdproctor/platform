package io.casehub.platform.agent.router.quarkus;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.router.RoutingAgentProvider;
import io.casehub.platform.agent.router.config.RoutingAgentConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class RouterBeans {

    @Produces
    @ApplicationScoped
    public RoutingAgentProvider routingAgentProvider(@Any Instance<AgentBackend> backends,
                                                     RoutingAgentConfig config) {
        return new RoutingAgentProvider(backends, config.defaultBackend());
    }
}
