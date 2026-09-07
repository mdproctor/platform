package io.casehub.platform.endpoints.memory;

import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistered;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class InMemoryEndpointRegistry implements EndpointRegistry {

    private static final Logger LOG = Logger.getLogger(InMemoryEndpointRegistry.class);

    private final ConcurrentHashMap<RegistryKey, EndpointDescriptor> store =
            new ConcurrentHashMap<>();

    private final Consumer<EndpointRegistered> onRegistered;

    public InMemoryEndpointRegistry(Consumer<EndpointRegistered> onRegistered) {
        this.onRegistered = onRegistered;
    }

    public InMemoryEndpointRegistry() {
        this(null);
    }

    @Override
    public void register(final EndpointDescriptor endpoint) {
        store.put(new RegistryKey(endpoint.path().value(), endpoint.tenancyId()), endpoint);
        if (onRegistered != null) {
            onRegistered.accept(new EndpointRegistered(endpoint));
        }
    }

    @Override
    public Optional<EndpointDescriptor> resolve(final Path path, final String tenancyId) {
        final EndpointDescriptor tenant = store.get(new RegistryKey(path.value(), tenancyId));
        if (tenant != null) return Optional.of(tenant);
        final EndpointDescriptor global = store.get(
                new RegistryKey(path.value(), TenancyConstants.PLATFORM_TENANT_ID));
        return Optional.ofNullable(global);
    }

    @Override
    public List<EndpointDescriptor> discover(final EndpointQuery query) {
        return store.values().stream()
                .filter(d -> matchesTenancy(d, query.tenancyId()))
                .filter(d -> query.type()     == null || d.type()     == query.type())
                .filter(d -> query.protocol() == null || d.protocol() == query.protocol())
                .filter(d -> d.capabilities().containsAll(query.requiredCapabilities()))
                .toList();
    }

    @Override
    public void deregister(final Path path, final String tenancyId) {
        store.remove(new RegistryKey(path.value(), tenancyId));
    }

    private static boolean matchesTenancy(final EndpointDescriptor d, final String tenancyId) {
        return d.tenancyId().equals(tenancyId)
                || d.tenancyId().equals(TenancyConstants.PLATFORM_TENANT_ID);
    }
}
