package io.casehub.platform.agent.openai;

import java.time.Duration;
import java.util.Optional;

public interface OpenAiAgentProperties {

    Optional<String> apiKey();

    String defaultModel();

    String promptCacheRetention();

    Duration defaultTimeout();

    int maxConcurrentSessions();
}
