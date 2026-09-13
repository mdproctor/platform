package io.casehub.platform.agent.router;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;

record InstanceWrapper(String key, String instanceId, AgentBackend delegate) implements AgentBackend {
    @Override
    public Multi<AgentEvent> invoke(AgentSessionConfig config) {
        return delegate.invoke(config);
    }

    @Override
    public AgentSession openSession(AgentSessionInit init) {
        return delegate.openSession(init);
    }
}
