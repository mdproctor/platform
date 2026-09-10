package io.casehub.platform.agent.langchain4j;

import java.time.Duration;

public interface AgentLangchain4jProperties {

    Duration closeTimeout();

    int sessionMemoryWindowSize();

    int maxConcurrentSessions();
}
