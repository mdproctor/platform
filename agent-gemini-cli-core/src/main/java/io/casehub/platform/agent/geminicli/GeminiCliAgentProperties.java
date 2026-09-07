package io.casehub.platform.agent.geminicli;

import java.time.Duration;

public interface GeminiCliAgentProperties {

    String binaryPath();

    Duration defaultTimeout();

    int maxConcurrentSessions();
}
