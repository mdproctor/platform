package io.casehub.platform.endpoints.memory;

import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistered;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volatile in-memory {@link EndpointRegistry}.
 *
 * <p>Tier 4 in the CDI priority ladder — {@code @Alternative @Priority(100)} beats
 * both JPA (Tier 2) and NoSQL (Tier 3) when on the classpath, following
 * {@code persistence-backend-cdi-priority.md} (PP-20260522-0cfa30) convention.
 *
 * <p>Thread-safe. Data is ephemeral (lost on restart). Suitable for tests and
 * zero-config ephemeral single-node installs. Do NOT combine with a JPA or NoSQL
 * endpoints backend in the same deployment scope.
 *
 * <p>{@link EndpointRegistry#discover(EndpointQuery)} iteration is weakly consistent —
 * concurrent modifications (register/deregister) may or may not be visible to an
 * in-flight discover call. This is acceptable for the in-memory use case.
 *
 * <h2>EndpointRegistered CDI event</h2>
 * <p>Fires {@link EndpointRegistered} via {@code fireAsync()} after every successful
 * {@link #register(EndpointDescriptor)} call. Observer exceptions are WARN-logged;
 * the registry operation itself has already succeeded before the event fires.
 * The CDI-proxy path (and unit tests) use a package-private no-arg constructor
 * that leaves {@code endpointRegisteredEvent} null; the null guard in
 * {@code register()} prevents NPE in those paths.
 */
@Alternative
@Priority(100)
@ApplicationScoped
public class InMemoryEndpointRegistry implements EndpointRegistry {

    private static final Logger LOG = Logger.getLogger(InMemoryEndpointRegistry.class);

    private final ConcurrentHashMap<RegistryKey, EndpointDescriptor> store =
            new ConcurrentHashMap<>();

    private final Event<EndpointRegistered> endpointRegisteredEvent;

    @Inject
    public InMemoryEndpointRegistry(Event<EndpointRegistered> endpointRegisteredEvent) {
        this.endpointRegisteredEvent = endpointRegisteredEvent;
    }

    /** Used by CDI proxy subclass (synthetic bytecode) and plain JUnit5 unit tests (same package). */
    InMemoryEndpointRegistry() {
        this.endpointRegisteredEvent = null;
    }

    @Override
    public void register(final EndpointDescriptor endpoint) {
        store.put(new RegistryKey(endpoint.path().value(), endpoint.tenancyId()), endpoint);
        if (endpointRegisteredEvent != null) {
            endpointRegisteredEvent.fireAsync(new EndpointRegistered(endpoint))
                .whenComplete((e, t) -> {
                    if (t != null) {
                        LOG.warnf(t, "EndpointRegistered observer failed for path %s",
                            endpoint.path());
                    }
                });
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
