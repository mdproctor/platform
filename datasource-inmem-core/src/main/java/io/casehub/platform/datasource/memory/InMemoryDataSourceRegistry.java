package io.casehub.platform.datasource.memory;

import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceDeregistered;
import io.casehub.platform.api.datasource.DataSourceDescriptor;
import io.casehub.platform.api.datasource.DataSourceQuery;
import io.casehub.platform.api.datasource.DataSourceRegistered;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.datasource.DataSourceUpdated;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.datasource.alpha.AlphaDataSource;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class InMemoryDataSourceRegistry implements DataSourceRegistry {

    private static final Logger LOG = Logger.getLogger(InMemoryDataSourceRegistry.class);

    private final ConcurrentHashMap<RegistryKey, DataSourceDescriptor> descriptors =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RegistryKey, DataSource<?>> sources =
            new ConcurrentHashMap<>();

    private final Consumer<DataSourceRegistered> onRegistered;
    private final Consumer<DataSourceDeregistered> onDeregistered;
    private final Consumer<DataSourceUpdated> onUpdated;

    public InMemoryDataSourceRegistry(Consumer<DataSourceRegistered> onRegistered,
                                       Consumer<DataSourceDeregistered> onDeregistered,
                                       Consumer<DataSourceUpdated> onUpdated) {
        this.onRegistered = onRegistered;
        this.onDeregistered = onDeregistered;
        this.onUpdated = onUpdated;
    }

    public InMemoryDataSourceRegistry() {
        this(null, null, null);
    }

    @Override
    public DataSource<?> register(final DataSourceDescriptor descriptor) {
        final RegistryKey key = new RegistryKey(descriptor.path().value(), descriptor.tenancyId());
        final boolean[] created = {false};

        DataSource<?> result = sources.compute(key, (k, existing) -> {
            if (existing instanceof AlphaDataSource<?> alpha && !alpha.isPendingRemoval()) {
                return existing;
            }
            created[0] = true;
            return new AlphaDataSource<>();
        });

        if (created[0]) {
            descriptors.put(key, descriptor);
            if (onRegistered != null) {
                onRegistered.accept(new DataSourceRegistered(descriptor));
            }
        }

        return result;
    }

    @Override
    public Optional<DataSourceDescriptor> resolve(final Path path, final String tenancyId) {
        final DataSourceDescriptor tenant = descriptors.get(
                new RegistryKey(path.value(), tenancyId));
        if (tenant != null) return Optional.of(tenant);
        final DataSourceDescriptor global = descriptors.get(
                new RegistryKey(path.value(), TenancyConstants.PLATFORM_TENANT_ID));
        return Optional.ofNullable(global);
    }

    @Override
    public Optional<DataSource<?>> resolveSource(final Path path, final String tenancyId) {
        final DataSource<?> tenant = sources.get(
                new RegistryKey(path.value(), tenancyId));
        if (tenant != null) return Optional.of(tenant);
        final DataSource<?> global = sources.get(
                new RegistryKey(path.value(), TenancyConstants.PLATFORM_TENANT_ID));
        return Optional.ofNullable(global);
    }

    @Override
    public List<DataSourceDescriptor> discover(final DataSourceQuery query) {
        return descriptors.values().stream()
                .filter(d -> matchesTenancy(d, query.tenancyId()))
                .filter(d -> query.objectType() == null
                        || d.objectType().getTypeKey().equals(query.objectType().getTypeKey()))
                .toList();
    }

    @Override
    public void deregister(final Path path, final String tenancyId) {
        final RegistryKey key = new RegistryKey(path.value(), tenancyId);
        final AlphaDataSource<?> source = (AlphaDataSource<?>) sources.get(key);
        if (source == null) {
            return;
        }
        final DataSourceDescriptor descriptor = descriptors.get(key);

        source.markForRemoval(() -> {
            if (sources.remove(key, source)) {
                descriptors.remove(key);
            }
        });

        if (descriptor != null && onDeregistered != null) {
            onDeregistered.accept(new DataSourceDeregistered(descriptor, source));
        }
    }

    @Override
    public void update(final DataSourceDescriptor descriptor) {
        final RegistryKey          key      = new RegistryKey(descriptor.path().value(), descriptor.tenancyId());
        final DataSourceDescriptor existing = descriptors.get(key);
        if (existing == null) {
            throw new IllegalStateException("No DataSource registered for path=" +
                                            descriptor.path() + ", tenancyId=" + descriptor.tenancyId());
        }
        if (!descriptor.objectType().getTypeKey().equals(existing.objectType().getTypeKey())) {
            throw new IllegalArgumentException(
                    "objectType is immutable — deregister and re-register to change type");
        }
        descriptors.put(key, descriptor);
        if (onUpdated != null) {
            DataSource<?> ds = sources.get(key);
            if (ds == null) {
                LOG.debugf("DataSource deregistered during update for path=%s — skipping event",
                        descriptor.path());
                return;
            }
            onUpdated.accept(new DataSourceUpdated(existing, descriptor, ds));
        }
    }

    private static boolean matchesTenancy(final DataSourceDescriptor d, final String tenancyId) {
        return d.tenancyId().equals(tenancyId)
                || d.tenancyId().equals(TenancyConstants.PLATFORM_TENANT_ID);
    }
}
