package io.casehub.platform.agent.codex;

import java.time.Duration;

public interface CodexAgentProperties {

    String binaryPath();

    Duration defaultTimeout();

    int maxConcurrentSessions();
}
