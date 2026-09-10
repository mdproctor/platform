package io.casehub.platform.agent.claude;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;

public class ClaudeAgentProvider implements AgentBackend {

    private final ClaudeAgentClient client;

    public ClaudeAgentProvider(ClaudeAgentClient client) {
        this.client = client;
    }

    @Override
    public String key() {return "claude";}

    @Override
    public Multi<AgentEvent> invoke(final AgentSessionConfig config) {
        return client.run(config);
    }

    @Override
    public AgentSession openSession(final AgentSessionInit init) {
        return client.openSession(init);
    }
}
