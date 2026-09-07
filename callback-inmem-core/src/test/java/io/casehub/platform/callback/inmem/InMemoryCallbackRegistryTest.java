package io.casehub.platform.callback.inmem;

import io.casehub.platform.api.callback.CallbackRegistrationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCallbackRegistryTest {

    private InMemoryCallbackRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryCallbackRegistry();
    }

    @Test
    void register_assignsIdAndExpiresAt() {
        var req = new CallbackRegistrationRequest(
                "worker-provisioner", "http://app:8080/callbacks",
                "app-cred", "tenant-1", 30000, 300, Map.of());
        var reg = registry.register(req);
        assertThat(reg.id()).isNotNull().isNotBlank();
        assertThat(reg.spiName()).isEqualTo("worker-provisioner");
        assertThat(reg.callbackUrl()).isEqualTo("http://app:8080/callbacks");
        assertThat(reg.credentialRef()).isEqualTo("app-cred");
        assertThat(reg.tenancyId()).isEqualTo("tenant-1");
        assertThat(reg.timeoutMs()).isEqualTo(30000);
        assertThat(reg.expiresAt()).isAfter(reg.registeredAt());
    }

    @Test
    void register_upsertBySpiUrlTenant() {
        var req = new CallbackRegistrationRequest(
                "worker-provisioner", "http://app:8080/callbacks",
                "app-cred", "tenant-1", 30000, 300, Map.of());
        var first = registry.register(req);
        var second = registry.register(req);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(registry.findBySpi("worker-provisioner", "tenant-1")).hasSize(1);
    }

    @Test
    void register_differentUrl_createsSeparateRegistration() {
        registry.register(new CallbackRegistrationRequest(
                "spi-a", "http://app1:8080/cb", null, "tenant-1", 30000, 300, Map.of()));
        registry.register(new CallbackRegistrationRequest(
                "spi-a", "http://app2:8080/cb", null, "tenant-1", 30000, 300, Map.of()));
        assertThat(registry.findBySpi("spi-a", "tenant-1")).hasSize(2);
    }

    @Test
    void findBySpi_filtersExpired() throws Exception {
        var req = new CallbackRegistrationRequest(
                "spi-a", "http://app:8080/cb",
                null, "tenant-1", 30000, 1, Map.of());
        registry.register(req);
        Thread.sleep(1100);
        assertThat(registry.findBySpi("spi-a", "tenant-1")).isEmpty();
    }

    @Test
    void findBySpi_filtersByTenant() {
        registry.register(new CallbackRegistrationRequest(
                "spi-a", "http://app:8080/cb", null, "tenant-1", 30000, 300, Map.of()));
        registry.register(new CallbackRegistrationRequest(
                "spi-a", "http://app:8080/cb", null, "tenant-2", 30000, 300, Map.of()));
        assertThat(registry.findBySpi("spi-a", "tenant-1")).hasSize(1);
        assertThat(registry.findBySpi("spi-a", "tenant-2")).hasSize(1);
    }

    @Test
    void heartbeat_extendsLease() {
        var req = new CallbackRegistrationRequest(
                "spi-a", "http://app:8080/cb",
                null, "tenant-1", 30000, 300, Map.of());
        var reg = registry.register(req);
        var originalExpiry = reg.expiresAt();
        registry.heartbeat(reg.id());
        var updated = registry.findById(reg.id()).orElseThrow();
        assertThat(updated.expiresAt()).isAfterOrEqualTo(originalExpiry);
        assertThat(updated.lastHeartbeatAt()).isAfterOrEqualTo(reg.lastHeartbeatAt());
    }

    @Test
    void heartbeat_unknownId_noOp() {
        registry.heartbeat("nonexistent");
    }

    @Test
    void deregister_removes() {
        var req = new CallbackRegistrationRequest(
                "spi-a", "http://app:8080/cb",
                null, "tenant-1", 30000, 300, Map.of());
        var reg = registry.register(req);
        registry.deregister(reg.id());
        assertThat(registry.findById(reg.id())).isEmpty();
        assertThat(registry.findBySpi("spi-a", "tenant-1")).isEmpty();
    }

    @Test
    void deregister_unknownId_noOp() {
        registry.deregister("nonexistent");
    }

    @Test
    void findBySpi_orderedByRegisteredAt() {
        registry.register(new CallbackRegistrationRequest(
                "spi-a", "http://app1:8080/cb",
                null, "tenant-1", 30000, 300, Map.of()));
        registry.register(new CallbackRegistrationRequest(
                "spi-a", "http://app2:8080/cb",
                null, "tenant-1", 30000, 300, Map.of()));
        var results = registry.findBySpi("spi-a", "tenant-1");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).registeredAt())
                .isBeforeOrEqualTo(results.get(1).registeredAt());
    }

    @Test
    void findById_returnsEmpty_forUnknown() {
        assertThat(registry.findById("nonexistent")).isEmpty();
    }

    @Test
    void register_preservesMetadata() {
        var meta = Map.of("app", "devtown", "version", "1.0");
        var req = new CallbackRegistrationRequest(
                "spi-a", "http://app:8080/cb",
                null, "tenant-1", 30000, 300, meta);
        var reg = registry.register(req);
        assertThat(reg.metadata()).containsEntry("app", "devtown");
        assertThat(reg.metadata()).containsEntry("version", "1.0");
    }
}
