package io.casehub.platform.agent;

import java.util.Objects;

public record BackendInstance(String instanceId, AgentBackend backend) {
    public BackendInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(backend, "backend");
    }
}
