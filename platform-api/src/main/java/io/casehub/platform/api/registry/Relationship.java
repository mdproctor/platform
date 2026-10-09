package io.casehub.platform.api.registry;

import java.util.Objects;

public record Relationship(String sourceId, String targetId, String type) {
    public Relationship {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(type, "type");
    }
}
