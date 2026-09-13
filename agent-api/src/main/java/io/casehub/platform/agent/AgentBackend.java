package io.casehub.platform.agent;

import io.smallrye.mutiny.Multi;

/**
 * Implementor-facing SPI for agent backends. Each backend registers with a unique
 * {@link #key()} and is discovered by the {@code RoutingAgentProvider} via CDI
 * {@code Instance<AgentBackend>}.
 *
 * <p>Callers never inject this directly — they inject {@link AgentProvider} and
 * specify the backend via {@link AgentSessionConfig#model()}.
 */
public interface AgentBackend {

    /**
     * Stable provider key used for routing (e.g. "claude", "openai", "codex",
     * "gemini", "gemini-cli", "langchain4j"). Must be unique across all backends
     * on the classpath.
     */
    String key();

    default String instanceId() {return "default";}


    Multi<AgentEvent> invoke(AgentSessionConfig config);

    AgentSession openSession(AgentSessionInit init);
}
