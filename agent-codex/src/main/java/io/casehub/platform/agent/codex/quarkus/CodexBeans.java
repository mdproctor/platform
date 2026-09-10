package io.casehub.platform.agent.codex.quarkus;

import io.casehub.platform.agent.AgentRuntime;
import io.casehub.platform.agent.codex.CodexAgentBackend;
import io.casehub.platform.agent.codex.config.CodexAgentConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class CodexBeans {

    private CodexAgentBackend backend;

    @Produces
    @ApplicationScoped
    public CodexAgentBackend codexAgentBackend(CodexAgentConfig config, AgentRuntime runtime) {
        backend = new CodexAgentBackend(config, runtime);
        return backend;
    }

    @PreDestroy
    void shutdown() {
        if (backend != null) {
            backend.shutdown();
        }
    }
}
