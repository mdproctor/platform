package io.casehub.platform.agent.gemini.quarkus;

import io.casehub.platform.agent.gemini.GeminiAgentBackend;
import io.casehub.platform.agent.gemini.config.GeminiAgentConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class GeminiBeans {

    private GeminiAgentBackend backend;

    @Produces
    @ApplicationScoped
    public GeminiAgentBackend geminiAgentBackend(GeminiAgentConfig config) {
        backend = new GeminiAgentBackend(config);
        return backend;
    }

    @PreDestroy
    void shutdown() {
        if (backend != null) {
            backend.shutdown();
        }
    }
}
