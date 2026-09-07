package io.casehub.platform.agent.openai.quarkus;

import io.casehub.platform.agent.openai.OpenAiAgentBackend;
import io.casehub.platform.agent.openai.config.OpenAiAgentConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class OpenAiBeans {

    private OpenAiAgentBackend backend;

    @Produces
    @ApplicationScoped
    public OpenAiAgentBackend openAiAgentBackend(OpenAiAgentConfig config) {
        backend = new OpenAiAgentBackend(config);
        return backend;
    }

    @PreDestroy
    void shutdown() {
        if (backend != null) {
            backend.shutdown();
        }
    }
}
