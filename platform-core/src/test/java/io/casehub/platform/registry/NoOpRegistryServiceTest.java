package io.casehub.platform.registry;

import io.casehub.platform.api.registry.CascadeAction;
import io.casehub.platform.api.registry.CascadeRule;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryEvent;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.api.registry.Relationship;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class NoOpRegistryServiceTest {

    private final RegistryService registry = new NoOpRegistryService();

    private RegistryEntry entry(String id, String type) {
        return new RegistryEntry(id, type, "default", "tenant-1",
                Map.of(), Instant.now(), null, Duration.ofSeconds(30),
                HealthStatus.HEALTHY);
    }

    @Test
    void registerAndResolveReturnsEmpty() {
        registry.register(entry("e1", "service"));
        assertThat(registry.resolve("e1")).isEmpty();
    }

    @Test
    void discoverReturnsEmpty() {
        registry.register(entry("e1", "service"));
        assertThat(registry.discover(new RegistryQuery("tenant-1", "service", "default"))).isEmpty();
    }

    @Test
    void heartbeatNoOp() {
        assertThatNoException().isThrownBy(() -> registry.heartbeat("e1"));
    }

    @Test
    void deregisterNoOp() {
        assertThatNoException().isThrownBy(() -> registry.deregister("e1"));
    }

    @Test
    void linkAndRelationshipsReturnEmpty() {
        registry.link(new Relationship("a", "b", "owns"));
        assertThat(registry.relationships("a")).isEmpty();
    }

    @Test
    void watchNoOp() {
        var events = new ArrayList<RegistryEvent>();
        registry.watch(new RegistryQuery("t", null, null), events::add);
        registry.register(entry("e1", "service"));
        assertThat(events).isEmpty();
    }

    @Test
    void cascadeRulesDefaultEmpty() {
        registry.registerCascadeRule(new CascadeRule("owns", CascadeAction.DEREGISTER));
        assertThat(registry.cascadeRules()).isEmpty();
    }
}
