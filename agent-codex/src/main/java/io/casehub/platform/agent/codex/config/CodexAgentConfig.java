package io.casehub.platform.agent.codex.config;

import io.casehub.platform.agent.codex.CodexAgentProperties;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

@ConfigMapping(prefix = "casehub.platform.agent.codex")
public interface CodexAgentConfig extends CodexAgentProperties {

    @Override
    @WithDefault("codex")
    String binaryPath();

    @Override
    @WithDefault("PT5M")
    Duration defaultTimeout();

    @Override
    @WithDefault("4")
    int maxConcurrentSessions();
}
