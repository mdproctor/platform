package io.casehub.platform.agent;

import java.util.Objects;

public record BackendRef(String key, String instanceId) {
    public BackendRef {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(instanceId, "instanceId");
    }
}
