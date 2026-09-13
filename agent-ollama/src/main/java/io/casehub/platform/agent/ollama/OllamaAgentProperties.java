package io.casehub.platform.agent.ollama;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "casehub.platform.agent.ollama")
public interface OllamaAgentProperties {

    @WithDefault("http://localhost:11434")
    String host();

    @WithDefault("llama3")
    String defaultModel();

    @WithDefault("PT120S")
    Duration defaultTimeout();

    @WithDefault("4")
    int maxConcurrentSessions();
}
