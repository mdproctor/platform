package io.casehub.platform.api.registry;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record RegistryEntry(
        String id,
        String type,
        String namespace,
        String tenancyId,
        Map<String, String> metadata,
        Instant registeredAt,
        Instant lastHeartbeat,
        Duration ttl,
        HealthStatus health
) {
    public RegistryEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(registeredAt, "registeredAt");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(health, "health");
        metadata = Map.copyOf(metadata);
    }

    public RegistryEntry withHealth(HealthStatus newHealth) {
        return new RegistryEntry(id, type, namespace, tenancyId,
                metadata, registeredAt, lastHeartbeat, ttl, newHealth);
    }

    public RegistryEntry withHeartbeat(Instant now) {
        return new RegistryEntry(id, type, namespace, tenancyId,
                metadata, registeredAt, now, ttl, HealthStatus.HEALTHY);
    }
}
