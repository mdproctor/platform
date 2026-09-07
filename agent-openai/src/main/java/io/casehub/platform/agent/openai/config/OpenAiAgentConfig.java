package io.casehub.platform.agent.openai.config;

import io.casehub.platform.agent.openai.OpenAiAgentProperties;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.platform.agent.openai")
public interface OpenAiAgentConfig extends OpenAiAgentProperties {

    @Override
    Optional<String> apiKey();

    @Override
    @WithDefault("gpt-4.1")
    String defaultModel();

    @Override
    @WithDefault("in_memory")
    String promptCacheRetention();

    @Override
    @WithDefault("PT5M")
    Duration defaultTimeout();

    @Override
    @WithDefault("4")
    int maxConcurrentSessions();
}
