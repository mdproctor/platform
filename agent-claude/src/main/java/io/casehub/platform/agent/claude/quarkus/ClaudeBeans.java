package io.casehub.platform.agent.claude.quarkus;

import io.casehub.platform.agent.claude.ClaudeAgentClient;
import io.casehub.platform.agent.claude.ClaudeAgentProvider;
import io.casehub.platform.agent.claude.config.ClaudeAgentConfig;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@Startup
@ApplicationScoped
public class ClaudeBeans {

    private ClaudeAgentClient client;

    @Produces
    @ApplicationScoped
    public ClaudeAgentClient claudeAgentClient(ClaudeAgentConfig config) {
        client = new ClaudeAgentClient(config);
        return client;
    }

    @Produces
    @ApplicationScoped
    public ClaudeAgentProvider claudeAgentProvider(ClaudeAgentClient client) {
        return new ClaudeAgentProvider(client);
    }

    @PostConstruct
    void validateBinary() {
        if (client != null) {
            client.validateBinary();
        }
    }

    @PreDestroy
    void shutdown() {
        if (client != null) {
            client.shutdown();
        }
    }
}
