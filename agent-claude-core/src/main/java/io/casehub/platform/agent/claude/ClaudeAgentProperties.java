package io.casehub.platform.agent.claude;

import java.time.Duration;
import java.util.Optional;

public interface ClaudeAgentProperties {

    Optional<String> binaryPath();

    Duration defaultTimeout();

    int maxConcurrentSessions();
}
