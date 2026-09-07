package io.casehub.platform.agent.router.config;

import io.casehub.platform.agent.router.RoutingAgentProperties;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.platform.agent")
public interface RoutingAgentConfig extends RoutingAgentProperties {

    @Override
    @WithDefault("claude")
    String defaultBackend();
}
