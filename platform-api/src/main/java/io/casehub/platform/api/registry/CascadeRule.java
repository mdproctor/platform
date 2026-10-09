package io.casehub.platform.api.registry;

import java.util.Objects;

public record CascadeRule(
        String relationshipType,
        CascadeAction onSourceDeregister
) {
    public CascadeRule {
        Objects.requireNonNull(relationshipType, "relationshipType");
        Objects.requireNonNull(onSourceDeregister, "onSourceDeregister");
    }
}
