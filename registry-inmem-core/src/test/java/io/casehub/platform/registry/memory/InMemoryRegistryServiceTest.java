package io.casehub.platform.registry.memory;

import io.casehub.platform.api.registry.CascadeAction;
import io.casehub.platform.api.registry.CascadeRule;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryEvent;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.Relationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class InMemoryRegistryServiceTest {

    private InMemoryRegistryService registry;
    private final ArrayList<RegistryEvent> cdiEvents = new ArrayList<>();
    private final ArrayList<RegistryEvent> watchEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = new InMemoryRegistryService(cdiEvents::add);
        cdiEvents.clear();
        watchEvents.clear();
    }

    private RegistryEntry entry(String id, String type, String namespace) {
        return new RegistryEntry(id, type, namespace, "tenant-1",
                Map.of(), Instant.now(), null, Duration.ofSeconds(30),
                HealthStatus.HEALTHY);
    }

    // --- register / resolve ---

    @Test
    void registerAndResolve() {
        var e = entry("svc-1", "service", "default");
        registry.register(e);
        assertThat(registry.resolve("svc-1")).isPresent()
                .hasValueSatisfying(r -> assertThat(r.id()).isEqualTo("svc-1"));
    }

    @Test
    void resolveUnknownReturnsEmpty() {
        assertThat(registry.resolve("nope")).isEmpty();
    }

    @Test
    void registerUpserts() {
        registry.register(entry("svc-1", "service", "default"));
        var updated = new RegistryEntry("svc-1", "service", "default", "tenant-1",
                Map.of("key", "val"), Instant.now(), null, Duration.ofSeconds(30),
                HealthStatus.HEALTHY);
        registry.register(updated);
        assertThat(registry.resolve("svc-1").get().metadata())
                .containsEntry("key", "val");
    }

    // --- deregister ---

    @Test
    void deregisterRemovesEntry() {
        registry.register(entry("svc-1", "service", "default"));
        registry.deregister("svc-1");
        assertThat(registry.resolve("svc-1")).isEmpty();
    }

    @Test
    void deregisterUnknownNoOp() {
        assertThatNoException().isThrownBy(() -> registry.deregister("nope"));
    }

    @Test
    void deregisterRemovesRelationships() {
        registry.register(entry("app-1", "app", "ns"));
        registry.register(entry("pool-1", "pool", "ns"));
        registry.link(new Relationship("app-1", "pool-1", "owns"));
        registry.deregister("app-1");
        assertThat(registry.relationships("app-1")).isEmpty();
        assertThat(registry.relationships("pool-1")).isEmpty();
    }

    // --- discover ---

    @Test
    void discoverByType() {
        registry.register(entry("svc-1", "service", "default"));
        registry.register(entry("pool-1", "pool", "default"));
        var results = registry.discover(new RegistryQuery("tenant-1", "service", null));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("svc-1");
    }

    @Test
    void discoverByNamespace() {
        registry.register(entry("svc-1", "service", "app-a"));
        registry.register(entry("svc-2", "service", "app-b"));
        var results = registry.discover(new RegistryQuery("tenant-1", null, "app-a"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("svc-1");
    }

    @Test
    void discoverByTypeAndNamespace() {
        registry.register(entry("svc-1", "service", "app-a"));
        registry.register(entry("pool-1", "pool", "app-a"));
        var results = registry.discover(new RegistryQuery("tenant-1", "pool", "app-a"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("pool-1");
    }

    @Test
    void discoverFiltersByTenancy() {
        registry.register(entry("svc-1", "service", "default"));
        var results = registry.discover(new RegistryQuery("other-tenant", null, null));
        assertThat(results).isEmpty();
    }

    @Test
    void discoverAllForTenant() {
        registry.register(entry("svc-1", "service", "default"));
        registry.register(entry("pool-1", "pool", "ns-a"));
        var results = registry.discover(new RegistryQuery("tenant-1", null, null));
        assertThat(results).hasSize(2);
    }

    // --- heartbeat ---

    @Test
    void heartbeatUpdatesTimestamp() {
        registry.register(entry("svc-1", "service", "default"));
        var before = registry.resolve("svc-1").get().lastHeartbeat();
        registry.heartbeat("svc-1");
        var after = registry.resolve("svc-1").get().lastHeartbeat();
        assertThat(after).isNotNull();
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void heartbeatResetsHealthToHealthy() {
        var down = entry("svc-1", "service", "default").withHealth(HealthStatus.DOWN);
        registry.register(down);
        registry.heartbeat("svc-1");
        assertThat(registry.resolve("svc-1").get().health()).isEqualTo(HealthStatus.HEALTHY);
    }

    @Test
    void heartbeatUnknownIdNoOp() {
        assertThatNoException().isThrownBy(() -> registry.heartbeat("nope"));
    }

    // --- relationships ---

    @Test
    void linkAndQueryRelationships() {
        registry.register(entry("app-1", "app", "ns"));
        registry.register(entry("pool-1", "pool", "ns"));
        registry.link(new Relationship("app-1", "pool-1", "owns"));
        assertThat(registry.relationships("app-1"))
                .hasSize(1)
                .first().satisfies(r -> {
                    assertThat(r.sourceId()).isEqualTo("app-1");
                    assertThat(r.targetId()).isEqualTo("pool-1");
                });
        assertThat(registry.relationships("pool-1")).hasSize(1);
    }

    @Test
    void unlinkRemovesRelationship() {
        registry.register(entry("app-1", "app", "ns"));
        registry.register(entry("pool-1", "pool", "ns"));
        registry.link(new Relationship("app-1", "pool-1", "owns"));
        registry.unlink("app-1", "pool-1");
        assertThat(registry.relationships("app-1")).isEmpty();
    }

    @Test
    void relationshipsOfUnknownIdEmpty() {
        assertThat(registry.relationships("unknown")).isEmpty();
    }

    // --- watch ---

    @Test
    void watchReceivesRegistrationEvents() {
        registry.watch(new RegistryQuery("tenant-1", null, null), watchEvents::add);
        registry.register(entry("svc-1", "service", "default"));
        assertThat(watchEvents).hasSize(1);
        assertThat(watchEvents.get(0).kind()).isEqualTo(RegistryEvent.EventKind.REGISTERED);
    }

    @Test
    void watchFiltersByType() {
        registry.watch(new RegistryQuery("tenant-1", "pool", null), watchEvents::add);
        registry.register(entry("svc-1", "service", "default"));
        registry.register(entry("pool-1", "pool", "default"));
        assertThat(watchEvents).hasSize(1);
        assertThat(watchEvents.get(0).entry().id()).isEqualTo("pool-1");
    }

    @Test
    void watchFiltersByTenancy() {
        registry.watch(new RegistryQuery("other-tenant", null, null), watchEvents::add);
        registry.register(entry("svc-1", "service", "default"));
        assertThat(watchEvents).isEmpty();
    }

    @Test
    void watchReceivesDeregistrationEvents() {
        registry.register(entry("svc-1", "service", "default"));
        registry.watch(new RegistryQuery("tenant-1", null, null), watchEvents::add);
        registry.deregister("svc-1");
        assertThat(watchEvents).hasSize(1);
        assertThat(watchEvents.get(0).kind()).isEqualTo(RegistryEvent.EventKind.DEREGISTERED);
    }

    // --- CDI events ---

    @Test
    void cdiEventFiredOnRegister() {
        registry.register(entry("svc-1", "service", "default"));
        assertThat(cdiEvents).hasSize(1);
        assertThat(cdiEvents.get(0).kind()).isEqualTo(RegistryEvent.EventKind.REGISTERED);
    }

    @Test
    void cdiEventFiredOnDeregister() {
        registry.register(entry("svc-1", "service", "default"));
        cdiEvents.clear();
        registry.deregister("svc-1");
        assertThat(cdiEvents).hasSize(1);
        assertThat(cdiEvents.get(0).kind()).isEqualTo(RegistryEvent.EventKind.DEREGISTERED);
    }

    @Test
    void cdiEventFiredOnLink() {
        registry.link(new Relationship("a", "b", "owns"));
        assertThat(cdiEvents).hasSize(1);
        assertThat(cdiEvents.get(0).kind()).isEqualTo(RegistryEvent.EventKind.LINKED);
    }

    @Test
    void cdiEventNotFiredOnDeregisterUnknown() {
        registry.deregister("nope");
        assertThat(cdiEvents).isEmpty();
    }
// --- unlink events ---

    @Test
    void unlinkFiresCdiEvent() {
        registry.register(entry("app-1", "app", "ns"));
        registry.register(entry("pool-1", "pool", "ns"));
        registry.link(new Relationship("app-1", "pool-1", "owns"));
        cdiEvents.clear();
        registry.unlink("app-1", "pool-1");
        assertThat(cdiEvents).hasSize(1);
        assertThat(cdiEvents.get(0).kind()).isEqualTo(RegistryEvent.EventKind.UNLINKED);
        assertThat(cdiEvents.get(0).relationship().sourceId()).isEqualTo("app-1");
    }

    @Test
    void unlinkUnknownPairNoEvent() {
        cdiEvents.clear();
        registry.unlink("x", "y");
        assertThat(cdiEvents).isEmpty();
    }

// --- watch edge cases ---

    @Test
    void watchReceivesHealthChangedEvents() {
        var entry = new RegistryEntry("svc-1", "service", "default", "tenant-1",
                                      Map.of(), Instant.now().minus(Duration.ofSeconds(60)), null,
                                      Duration.ofSeconds(5), HealthStatus.HEALTHY);
        registry.register(entry);
        registry.watch(new RegistryQuery("tenant-1", null, null), watchEvents::add);
        var scheduler = new HeartbeatScheduler(registry);
        scheduler.checkHeartbeats();
        assertThat(watchEvents).anyMatch(e ->
                                                 e.kind() == RegistryEvent.EventKind.HEALTH_CHANGED);
    }

    @Test
    void watchFiltersByNamespace() {
        registry.watch(new RegistryQuery("tenant-1", null, "app-a"), watchEvents::add);
        registry.register(entry("svc-1", "service", "app-a"));
        registry.register(entry("svc-2", "service", "app-b"));
        assertThat(watchEvents).hasSize(1);
        assertThat(watchEvents.get(0).entry().id()).isEqualTo("svc-1");
    }


// --- cascade rules ---

    @Test
    void registerAndRetrieveCascadeRule() {
        registry.registerCascadeRule(new CascadeRule("owns", CascadeAction.DEREGISTER));
        assertThat(registry.cascadeRules()).hasSize(1);
        assertThat(registry.cascadeRules().get(0).relationshipType()).isEqualTo("owns");
    }

    @Test
    void cascadeRuleUpsertsByRelationshipType() {
        registry.registerCascadeRule(new CascadeRule("owns", CascadeAction.NOTIFY));
        registry.registerCascadeRule(new CascadeRule("owns", CascadeAction.DEREGISTER));
        assertThat(registry.cascadeRules()).hasSize(1);
        assertThat(registry.cascadeRules().get(0).onSourceDeregister()).isEqualTo(CascadeAction.DEREGISTER);
    }

    @Test
    void cascadeRulesInitiallyEmpty() {
        assertThat(registry.cascadeRules()).isEmpty();
    }
}
