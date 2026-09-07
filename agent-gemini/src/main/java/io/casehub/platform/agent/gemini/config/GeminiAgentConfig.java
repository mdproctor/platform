package io.casehub.platform.agent.gemini.config;

import io.casehub.platform.agent.gemini.GeminiAgentProperties;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.platform.agent.gemini")
public interface GeminiAgentConfig extends GeminiAgentProperties {

    @Override
    Optional<String> apiKey();

    @Override
    @WithDefault("gemini-2.5-flash")
    String defaultModel();

    @Override
    @WithDefault("PT1H")
    Duration cacheTtl();

    @Override
    @WithDefault("PT5M")
    Duration defaultTimeout();

    @Override
    @WithDefault("4")
    int maxConcurrentSessions();
}
