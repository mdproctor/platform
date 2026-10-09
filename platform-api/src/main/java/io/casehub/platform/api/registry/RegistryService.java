package io.casehub.platform.api.registry;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Unified registration, discovery, and lifecycle management for runtime entities.
 *
 * <p>Sibling to {@link io.casehub.platform.api.endpoints.EndpointRegistry EndpointRegistry} —
 * they serve different key models. EndpointRegistry uses {@code (Path, tenancyId)} for named
 * endpoints. RegistryService uses {@code (id, type, namespace)} for runtime service topology
 * with heartbeat, TTL, health, and relationship tracking.
 *
 * <p>{@code NoOpRegistryService @DefaultBean} is active when no backend module is on the
 * classpath. {@code InMemoryRegistryService @Alternative @Priority(50)} in
 * {@code casehub-platform-registry-inmem} provides a working in-memory backend.
 *
 * <h2>Entity lifecycle</h2>
 * <p>Register with a TTL. Send heartbeats to stay alive. Entries that miss their TTL
 * window transition to {@link HealthStatus#DOWN} and fire
 * {@link RegistryEvent.EventKind#HEALTH_CHANGED}.
 *
 * <h2>Relationships</h2>
 * <p>Link entries via typed relationships (owns, runs-on, serves, consumes).
 * Deregistering an entry removes all its relationships.
 *
 * <h2>CDI events</h2>
 * <p>Non-no-op implementations fire {@link RegistryEvent} via {@code Event.fireAsync()}
 * on register, deregister, health change, link, and unlink. The no-op {@code @DefaultBean}
 * must NOT fire events.
 */
public interface RegistryService {

    void register(RegistryEntry entry);

    void heartbeat(String id);

    void deregister(String id);

    Optional<RegistryEntry> resolve(String id);

    List<RegistryEntry> discover(RegistryQuery query);

    void link(Relationship rel);

    void unlink(String sourceId, String targetId);

    List<Relationship> relationships(String id);

    void watch(RegistryQuery query, Consumer<RegistryEvent> listener);

    default void registerCascadeRule(CascadeRule rule) {}

    default List<CascadeRule> cascadeRules()           {return List.of();}
}
