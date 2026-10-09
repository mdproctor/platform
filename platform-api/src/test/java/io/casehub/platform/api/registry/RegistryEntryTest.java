package io.casehub.platform.api.registry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryEntryTest {

    @Test
    void minimalEntry() {
        var entry = new RegistryEntry(
                "fleet-1", "service", "default", "tenant-1",
                Map.of(), Instant.now(), null, Duration.ofSeconds(30),
                HealthStatus.HEALTHY);
        assertThat(entry.id()).isEqualTo("fleet-1");
        assertThat(entry.type()).isEqualTo("service");
        assertThat(entry.namespace()).isEqualTo("default");
        assertThat(entry.health()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(entry.lastHeartbeat()).isNull();
    }

    @Test
    void nullIdThrows() {
        assertThatThrownBy(() -> new RegistryEntry(
                null, "service", "default", "t", Map.of(),
                Instant.now(), null, Duration.ofSeconds(30), HealthStatus.HEALTHY
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTypeThrows() {
        assertThatThrownBy(() -> new RegistryEntry(
                "e1", null, "default", "t", Map.of(),
                Instant.now(), null, Duration.ofSeconds(30), HealthStatus.HEALTHY
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullNamespaceThrows() {
        assertThatThrownBy(() -> new RegistryEntry(
                "e1", "service", null, "t", Map.of(),
                Instant.now(), null, Duration.ofSeconds(30), HealthStatus.HEALTHY
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTenancyIdThrows() {
        assertThatThrownBy(() -> new RegistryEntry(
                "e1", "service", "default", null, Map.of(),
                Instant.now(), null, Duration.ofSeconds(30), HealthStatus.HEALTHY
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullMetadataThrows() {
        assertThatThrownBy(() -> new RegistryEntry(
                "e1", "service", "default", "t", null,
                Instant.now(), null, Duration.ofSeconds(30), HealthStatus.HEALTHY
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullRegisteredAtThrows() {
        assertThatThrownBy(() -> new RegistryEntry(
                "e1", "service", "default", "t", Map.of(),
                null, null, Duration.ofSeconds(30), HealthStatus.HEALTHY
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTtlThrows() {
        assertThatThrownBy(() -> new RegistryEntry(
                "e1", "service", "default", "t", Map.of(),
                Instant.now(), null, null, HealthStatus.HEALTHY
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullHealthThrows() {
        assertThatThrownBy(() -> new RegistryEntry(
                "e1", "service", "default", "t", Map.of(),
                Instant.now(), null, Duration.ofSeconds(30), null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void relationshipNullTargetThrows() {
        assertThatThrownBy(() -> new Relationship("app-1", null, "owns"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void relationshipNullTypeThrows() {
        assertThatThrownBy(() -> new Relationship("app-1", "pool-1", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void cascadeRuleNullActionThrows() {
        assertThatThrownBy(() -> new CascadeRule("owns", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registryEventDeregisteredFactory() {
        var entry = new RegistryEntry("e1", "service", "default", "t", Map.of(),
                                      Instant.now(), null, Duration.ofSeconds(30), HealthStatus.HEALTHY);
        var event = RegistryEvent.deregistered(entry);
        assertThat(event.kind()).isEqualTo(RegistryEvent.EventKind.DEREGISTERED);
        assertThat(event.entry()).isSameAs(entry);
    }

    @Test
    void registryEventHealthChangedFactory() {
        var entry = new RegistryEntry("e1", "service", "default", "t", Map.of(),
                                      Instant.now(), null, Duration.ofSeconds(30), HealthStatus.DOWN);
        var event = RegistryEvent.healthChanged(entry);
        assertThat(event.kind()).isEqualTo(RegistryEvent.EventKind.HEALTH_CHANGED);
    }

    @Test
    void registryEventHeartbeatExpiredFactory() {
        var entry = new RegistryEntry("e1", "service", "default", "t", Map.of(),
                                      Instant.now(), null, Duration.ofSeconds(30), HealthStatus.DOWN);
        var event = RegistryEvent.heartbeatExpired(entry);
        assertThat(event.kind()).isEqualTo(RegistryEvent.EventKind.HEARTBEAT_EXPIRED);
    }

    @Test
    void registryEventUnlinkedFactory() {
        var rel   = new Relationship("a", "b", "owns");
        var event = RegistryEvent.unlinked(rel);
        assertThat(event.kind()).isEqualTo(RegistryEvent.EventKind.UNLINKED);
        assertThat(event.relationship()).isSameAs(rel);
    }


    @Test
    void metadataIsImmutableCopy() {
        var props = new java.util.HashMap<String, String>();
        props.put("key", "value");
        var entry = new RegistryEntry(
                "e1", "service", "default", "t", props,
                Instant.now(), null, Duration.ofSeconds(30), HealthStatus.HEALTHY);
        assertThatThrownBy(() -> entry.metadata().put("new", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
        props.put("mutated", "yes");
        assertThat(entry.metadata()).doesNotContainKey("mutated");
    }

    @Test
    void withHealthReturnsNewEntry() {
        var entry = new RegistryEntry(
                "e1", "service", "default", "t", Map.of(),
                Instant.now(), null, Duration.ofSeconds(30), HealthStatus.HEALTHY);
        var down = entry.withHealth(HealthStatus.DOWN);
        assertThat(down.health()).isEqualTo(HealthStatus.DOWN);
        assertThat(entry.health()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(down.id()).isEqualTo(entry.id());
    }

    @Test
    void withHeartbeatSetsTimestampAndHealthy() {
        var entry = new RegistryEntry(
                "e1", "service", "default", "t", Map.of(),
                Instant.now(), null, Duration.ofSeconds(30), HealthStatus.DOWN);
        var now = Instant.now();
        var refreshed = entry.withHeartbeat(now);
        assertThat(refreshed.lastHeartbeat()).isEqualTo(now);
        assertThat(refreshed.health()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(entry.health()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void relationshipNullSourceThrows() {
        assertThatThrownBy(() -> new Relationship(null, "pool-1", "owns"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void relationshipRecord() {
        var rel = new Relationship("app-1", "pool-1", "owns");
        assertThat(rel.sourceId()).isEqualTo("app-1");
        assertThat(rel.targetId()).isEqualTo("pool-1");
        assertThat(rel.type()).isEqualTo("owns");
    }

    @Test
    void registryQueryRequiresTenancyId() {
        assertThatThrownBy(() -> new RegistryQuery(null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registryQueryWildcards() {
        var query = new RegistryQuery("tenant-1", null, null);
        assertThat(query.type()).isNull();
        assertThat(query.namespace()).isNull();
    }

    @Test
    void registryEventFactories() {
        var entry = new RegistryEntry(
                "e1", "service", "default", "t", Map.of(),
                Instant.now(), null, Duration.ofSeconds(30), HealthStatus.HEALTHY);
        var registered = RegistryEvent.registered(entry);
        assertThat(registered.kind()).isEqualTo(RegistryEvent.EventKind.REGISTERED);
        assertThat(registered.entry()).isSameAs(entry);
        assertThat(registered.relationship()).isNull();

        var rel = new Relationship("a", "b", "owns");
        var linked = RegistryEvent.linked(rel);
        assertThat(linked.kind()).isEqualTo(RegistryEvent.EventKind.LINKED);
        assertThat(linked.relationship()).isSameAs(rel);
        assertThat(linked.entry()).isNull();
    }

    @Test
    void healthStatusValues() {
        assertThat(HealthStatus.values()).containsExactly(
                HealthStatus.HEALTHY, HealthStatus.DEGRADED, HealthStatus.DOWN);
    }

    @Test
    void cascadeRuleRecord() {
        var rule = new CascadeRule("owns", CascadeAction.DEREGISTER);
        assertThat(rule.relationshipType()).isEqualTo("owns");
        assertThat(rule.onSourceDeregister()).isEqualTo(CascadeAction.DEREGISTER);
    }

    @Test
    void cascadeRuleNullTypeThrows() {
        assertThatThrownBy(() -> new CascadeRule(null, CascadeAction.NONE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void cascadeActionValues() {
        assertThat(CascadeAction.values()).containsExactly(
                CascadeAction.DEREGISTER, CascadeAction.MARK_ORPHANED,
                CascadeAction.NOTIFY, CascadeAction.NONE);
    }
}
