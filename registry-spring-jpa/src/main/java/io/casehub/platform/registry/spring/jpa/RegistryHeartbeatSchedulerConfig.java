package io.casehub.platform.registry.spring.jpa;

import io.casehub.platform.registry.jpa.JpaRegistryService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

@AutoConfiguration
@ConditionalOnBean(JpaRegistryService.class)
@EnableScheduling
public class RegistryHeartbeatSchedulerConfig {

    private final JpaRegistryService registry;

    public RegistryHeartbeatSchedulerConfig(JpaRegistryService registry) {
        this.registry = registry;
    }

    @Scheduled(fixedRate = 10_000)
    @Transactional
    public void checkHeartbeats() {
        registry.checkHeartbeats();
    }
}
