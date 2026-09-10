package io.casehub.platform.agent.geminicli.config;

import io.casehub.platform.agent.geminicli.GeminiCliAgentProperties;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

@ConfigMapping(prefix = "casehub.platform.agent.gemini-cli")
public interface GeminiCliAgentConfig extends GeminiCliAgentProperties {

    @Override
    @WithDefault("gemini")
    String binaryPath();

    @Override
    @WithDefault("PT5M")
    Duration defaultTimeout();

    @Override
    @WithDefault("4")
    int maxConcurrentSessions();
}
