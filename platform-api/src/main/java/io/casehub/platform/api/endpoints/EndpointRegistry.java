package io.casehub.platform.api.endpoints;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;

import java.util.List;
import java.util.Optional;

/**
 * Tenant-scoped registry of named endpoints.
 *
 * <p>Provides registration, resolution, discovery, and deregistration of
 * {@link EndpointDescriptor} instances keyed by {@code (path, tenancyId)}.
 *
 * <p>{@code NoOpEndpointRegistry @DefaultBean} is active when no backend module is on
 * the classpath. {@code InMemoryEndpointRegistry @Alternative @Priority(100)} in
 * {@code casehub-platform-endpoints-memory} provides a working in-memory backend.
 *
 * <h2>Tenant isolation</h2>
 * <p>All read operations filter by {@code tenancyId}. Platform-global endpoints registered
 * with {@link TenancyConstants#PLATFORM_TENANT_ID} are visible to all tenants.
 *
 * <h2>Write authorization</h2>
 * <p>The SPI enforces no write authorization — callers are accountable for ensuring
 * {@link EndpointDescriptor#tenancyId()} matches their authority. The initial population
 * model ({@code @Startup @PostConstruct}) operates with implicit system authority.
 *
 * <h2>EndpointRegistered CDI event</h2>
 * <p>Non-no-op implementations have a required obligation to fire
 * {@link EndpointRegistered} via {@code Event<EndpointRegistered>.fireAsync()} after
 * every successful {@link #register(EndpointDescriptor)} call. The no-op
 * {@code @DefaultBean} implementation must NOT fire the event — it stores nothing,
 * and firing would trigger stream route creation for phantom endpoints.
 */
public interface EndpointRegistry {

    /**
     * Register or update an endpoint. {@code (path, tenancyId)} is the unique key —
     * upsert semantics: re-registering the same key replaces the descriptor.
     *
     * <p>A single descriptor per {@code (path, tenancyId)} declares the complete
     * capability set. Multiple registrations for the same key resolve by last write —
     * there is no merge semantics.
     */
    void register(EndpointDescriptor endpoint);

    /**
     * Resolve an endpoint by path for the given tenant, applying priority lookup.
     *
     * <ol>
     *   <li>Returns the tenant-specific endpoint if one exists
     *       ({@code descriptor.tenancyId().equals(tenancyId)}).</li>
     *   <li>Otherwise returns the platform-global endpoint if one exists
     *       ({@code descriptor.tenancyId().equals(PLATFORM_TENANT_ID)}).</li>
     *   <li>Otherwise returns empty.</li>
     * </ol>
     *
     * <p>Tenant-specific takes precedence — allows tenants to override platform defaults.
     *
     * <p>Cross-tenant admin resolution is the caller's responsibility — pass the target
     * tenancyId directly. The SPI has no principal awareness on reads.
     */
    Optional<EndpointDescriptor> resolve(Path path, String tenancyId);

    /**
     * Discover endpoints matching the query criteria.
     *
     * <p>Always includes platform-global endpoints alongside the caller's tenant endpoints.
     * Returns both tenant-specific and platform-global matches — no override semantics;
     * use {@link #resolve(Path, String)} when a single authoritative result is required.
     *
     * <p>Complete predicate — see {@link EndpointQuery} for the full four-condition
     * conjunction that every implementation must enforce.
     *
     * <p>The result list is unordered. Implementations must not guarantee a specific
     * ordering, and callers must not depend on one.
     */
    List<EndpointDescriptor> discover(EndpointQuery query);

    /**
     * Deregister by {@code (path, tenancyId)}. No-op if not found.
     */
    void deregister(Path path, String tenancyId);
}
