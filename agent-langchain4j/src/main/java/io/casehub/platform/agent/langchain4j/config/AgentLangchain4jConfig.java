package io.casehub.platform.agent.langchain4j.config;

import io.casehub.platform.agent.langchain4j.AgentLangchain4jProperties;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "casehub.platform.agent.langchain4j")
public interface AgentLangchain4jConfig extends AgentLangchain4jProperties {

    @Override
    @WithDefault("PT30S")
    Duration closeTimeout();

    @Override
    @WithDefault("20")
    int sessionMemoryWindowSize();

    @Override
    @WithDefault("10")
    int maxConcurrentSessions();
}
