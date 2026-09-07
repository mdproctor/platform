package io.casehub.platform.agent.claude.config;

import io.casehub.platform.agent.claude.ClaudeAgentProperties;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.platform.agent.claude")
public interface ClaudeAgentConfig extends ClaudeAgentProperties {

    @Override
    Optional<String> binaryPath();

    @Override
    @WithDefault("PT5M")
    Duration defaultTimeout();

    @Override
    @WithDefault("4")
    int maxConcurrentSessions();
}
