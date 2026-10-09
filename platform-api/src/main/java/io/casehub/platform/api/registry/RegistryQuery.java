package io.casehub.platform.api.registry;

import java.util.Objects;

public record RegistryQuery(
        String tenancyId,
        String type,
        String namespace
) {
    public RegistryQuery {
        Objects.requireNonNull(tenancyId, "tenancyId");
    }
}
