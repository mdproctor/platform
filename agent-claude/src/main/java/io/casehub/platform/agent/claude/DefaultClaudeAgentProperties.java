package io.casehub.platform.agent.claude;

import java.time.Duration;
import java.util.Optional;

record DefaultClaudeAgentProperties(
        Optional<String> binaryPath,
        Duration defaultTimeout,
        int maxConcurrentSessions
) implements ClaudeAgentProperties {

    DefaultClaudeAgentProperties() {
        this(Optional.empty(), Duration.ofMinutes(5), 4);
    }
}
