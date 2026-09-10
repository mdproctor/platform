package io.casehub.platform.datasource.memory;

import io.casehub.platform.api.datasource.ClassObjectType;
import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceDescriptor;
import io.casehub.platform.api.datasource.DataSourceQuery;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryDataSourceRegistryTest {

    private final InMemoryDataSourceRegistry registry = new InMemoryDataSourceRegistry();

    private DataSourceDescriptor descriptor(String path, String tenancyId) {
        return new DataSourceDescriptor(
                Path.parse(path), tenancyId,
                new ClassObjectType<>(Object.class), null,
                Set.of(), Map.of(), Map.of());
    }

    @Test
    void register_returnsDataSource() {
        DataSource<?> ds = registry.register(descriptor("test", "t1"));
        assertThat(ds).isNotNull();
    }

    @Test
    void resolve_tenantSpecific() {
        registry.register(descriptor("test", "t1"));
        assertThat(registry.resolve(Path.parse("test"), "t1")).isPresent();
        assertThat(registry.resolve(Path.parse("test"), "t2")).isEmpty();
    }

    @Test
    void resolve_platformGlobalFallback() {
        registry.register(descriptor("global", TenancyConstants.PLATFORM_TENANT_ID));
        assertThat(registry.resolve(Path.parse("global"), "any-tenant")).isPresent();
    }

    @Test
    void resolve_tenantOverridesPlatform() {
        registry.register(descriptor("path", TenancyConstants.PLATFORM_TENANT_ID));
        registry.register(descriptor("path", "t1"));
        var result = registry.resolve(Path.parse("path"), "t1");
        assertThat(result).isPresent();
        assertThat(result.get().tenancyId()).isEqualTo("t1");
    }

    @Test
    void resolveSource_returnsRuntime() {
        registry.register(descriptor("test", "t1"));
        assertThat(registry.resolveSource(Path.parse("test"), "t1")).isPresent();
    }

    @Test
    void discover_includesPlatformGlobal() {
        registry.register(descriptor("a", "t1"));
        registry.register(descriptor("b", TenancyConstants.PLATFORM_TENANT_ID));
        var results = registry.discover(new DataSourceQuery("t1", null));
        assertThat(results).hasSize(2);
    }

    @Test
    void discover_excludesCrossTenant() {
        registry.register(descriptor("a", "t1"));
        registry.register(descriptor("b", "t2"));
        var results = registry.discover(new DataSourceQuery("t1", null));
        assertThat(results).hasSize(1);
    }

    @Test
    void deregister_removesBoth() {
        registry.register(descriptor("test", "t1"));
        registry.deregister(Path.parse("test"), "t1");
        assertThat(registry.resolve(Path.parse("test"), "t1")).isEmpty();
        assertThat(registry.resolveSource(Path.parse("test"), "t1")).isEmpty();
    }

    // --- Idempotent register ---

    @Test
    void register_idempotent_returnsSameDataSource() {
        DataSource<?> ds1 = registry.register(descriptor("test", "t1"));
        DataSource<?> ds2 = registry.register(descriptor("test", "t1"));
        assertThat(ds2).isSameAs(ds1);
    }

    // --- Lifecycle-aware deregister ---

    @Test
    void deregister_noSubscribers_cleansImmediately() {
        registry.register(descriptor("test", "t1"));
        registry.deregister(Path.parse("test"), "t1");
        assertThat(registry.resolve(Path.parse("test"), "t1")).isEmpty();
        assertThat(registry.resolveSource(Path.parse("test"), "t1")).isEmpty();
    }

    @Test
    void deregister_activeSubscribers_defersCleanup() {
        DataSource<?> ds = registry.register(descriptor("test", "t1"));
        @SuppressWarnings("unchecked")
        var handle = ((DataSource<Object>) ds).subscribe(obj -> {});

        registry.deregister(Path.parse("test"), "t1");

        assertThat(registry.resolve(Path.parse("test"), "t1")).isPresent();
        assertThat(registry.resolveSource(Path.parse("test"), "t1")).isPresent();

        handle.unsubscribe();

        assertThat(registry.resolve(Path.parse("test"), "t1")).isEmpty();
        assertThat(registry.resolveSource(Path.parse("test"), "t1")).isEmpty();
    }

    @Test
    void register_duringDrain_createsNewDataSource() {
        DataSource<?> ds1 = registry.register(descriptor("test", "t1"));
        @SuppressWarnings("unchecked")
        var handle = ((DataSource<Object>) ds1).subscribe(obj -> {});

        registry.deregister(Path.parse("test"), "t1");

        DataSource<?> ds2 = registry.register(descriptor("test", "t1"));
        assertThat(ds2).isNotSameAs(ds1);

        handle.unsubscribe();
        assertThat(registry.resolveSource(Path.parse("test"), "t1")).isPresent();
        assertThat(registry.resolveSource(Path.parse("test"), "t1").get()).isSameAs(ds2);
    }

    @Test
    void deregister_unknownKey_noOp() {
        registry.deregister(Path.parse("nonexistent"), "t1");
    }

    // --- Update ---

    @Test
    void update_replacesDescriptor() {
        registry.register(descriptor("test", "t1"));
        var updated = new DataSourceDescriptor(
                Path.parse("test"), "t1", new ClassObjectType<>(Object.class), Path.parse("new-ep"),
                Set.of("order.created"), Map.of("k", "v"), Map.of());
        registry.update(updated);
        assertThat(registry.resolve(Path.parse("test"), "t1")).contains(updated);
    }

    @Test
    void update_preservesDataSourceInstance() {
        DataSource<?> ds = registry.register(descriptor("test", "t1"));
        var updated = new DataSourceDescriptor(
                Path.parse("test"), "t1", new ClassObjectType<>(Object.class), Path.parse("new-ep"),
                Set.of(), Map.of(), Map.of());
        registry.update(updated);
        assertThat(registry.resolveSource(Path.parse("test"), "t1")).containsSame(ds);
    }

    @Test
    void update_throwsIfNotFound() {
        assertThatThrownBy(() -> registry.update(descriptor("nonexistent", "t1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void update_throwsIfObjectTypeChanges() {
        registry.register(descriptor("test", "t1"));
        var changed = new DataSourceDescriptor(
                Path.parse("test"), "t1", new ClassObjectType<>(String.class), null,
                Set.of(), Map.of(), Map.of());
        assertThatThrownBy(() -> registry.update(changed))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
