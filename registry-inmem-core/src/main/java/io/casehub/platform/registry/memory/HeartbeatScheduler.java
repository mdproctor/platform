package io.casehub.platform.registry.memory;

import io.casehub.platform.api.registry.HealthStatus;

import java.time.Instant;

public class HeartbeatScheduler {

    private final InMemoryRegistryService registry;

    public HeartbeatScheduler(InMemoryRegistryService registry) {
        this.registry = registry;
    }

    public void checkHeartbeats() {
        var now = Instant.now();
        for (var entry : registry.allEntries()) {
            if (entry.health() == HealthStatus.DOWN) continue;
            var lastSeen = entry.lastHeartbeat() != null
                    ? entry.lastHeartbeat()
                    : entry.registeredAt();
            if (now.isAfter(lastSeen.plus(entry.ttl()))) {
                registry.updateEntry(entry.withHealth(HealthStatus.DOWN));
            }
        }
    }
}
