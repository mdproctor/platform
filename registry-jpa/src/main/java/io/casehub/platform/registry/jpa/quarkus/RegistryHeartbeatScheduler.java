package io.casehub.platform.registry.jpa.quarkus;

import io.casehub.platform.registry.jpa.JpaRegistryService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class RegistryHeartbeatScheduler {

    @Inject
    JpaRegistryService registry;

    @Scheduled(every = "10s", identity = "registry-heartbeat-check")
    @Transactional
    void checkHeartbeats() {
        registry.checkHeartbeats();
    }
}
