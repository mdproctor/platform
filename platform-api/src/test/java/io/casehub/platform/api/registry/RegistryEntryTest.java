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
}
