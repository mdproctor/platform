package io.casehub.platform.agent.geminicli.quarkus;

import io.casehub.platform.agent.AgentRuntime;
import io.casehub.platform.agent.geminicli.GeminiCliAgentBackend;
import io.casehub.platform.agent.geminicli.config.GeminiCliAgentConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class GeminiCliBeans {

    private GeminiCliAgentBackend backend;

    @Produces
    @ApplicationScoped
    public GeminiCliAgentBackend geminiCliAgentBackend(GeminiCliAgentConfig config, AgentRuntime runtime) {
        backend = new GeminiCliAgentBackend(config, runtime);
        return backend;
    }

    @PreDestroy
    void shutdown() {
        if (backend != null) {
            backend.shutdown();
        }
    }
}
