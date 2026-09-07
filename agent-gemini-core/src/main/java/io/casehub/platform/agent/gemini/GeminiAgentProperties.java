package io.casehub.platform.agent.gemini;

import java.time.Duration;
import java.util.Optional;

public interface GeminiAgentProperties {

    Optional<String> apiKey();

    String defaultModel();

    Duration cacheTtl();

    Duration defaultTimeout();

    int maxConcurrentSessions();
}
