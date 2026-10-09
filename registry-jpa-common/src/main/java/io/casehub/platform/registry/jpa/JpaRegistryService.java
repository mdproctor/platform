package io.casehub.platform.registry.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.registry.CascadeAction;
import io.casehub.platform.api.registry.CascadeRule;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryEvent;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.api.registry.Relationship;
import jakarta.persistence.EntityManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class JpaRegistryService implements RegistryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final EntityManager em;
    private final Consumer<RegistryEvent> eventSink;
    private final CopyOnWriteArrayList<WatchSubscription> watchers = new CopyOnWriteArrayList<>();

    public JpaRegistryService(EntityManager em, Consumer<RegistryEvent> eventSink) {
        this.em = Objects.requireNonNull(em);
        this.eventSink = Objects.requireNonNull(eventSink);
    }

    @Override
    public void register(RegistryEntry entry) {
        var entity = em.find(RegistryEntryEntity.class, entry.id());
        if (entity == null) {
            entity = new RegistryEntryEntity();
            entity.id = entry.id();
        }
        entity.type = entry.type();
        entity.namespace = entry.namespace();
        entity.tenancyId = entry.tenancyId();
        entity.metadata = serializeMetadata(entry.metadata());
        entity.registeredAt = entry.registeredAt();
        entity.lastHeartbeat = entry.lastHeartbeat();
        entity.ttlSeconds = entry.ttl().toSeconds();
        entity.health = entry.health().name();
        em.merge(entity);
        var event = RegistryEvent.registered(entry);
        eventSink.accept(event);
        notifyWatchers(event);
    }

    @Override
    public void heartbeat(String id) {
        var entity = em.find(RegistryEntryEntity.class, id);
        if (entity != null) {
            entity.lastHeartbeat = Instant.now();
            entity.health        = HealthStatus.HEALTHY.name();
        }
    }

    @Override
    public void deregister(String id) {
        var entity = em.find(RegistryEntryEntity.class, id);
        if (entity != null) {
            em.createQuery("DELETE FROM RelationshipEntity r WHERE r.sourceId = :id OR r.targetId = :id")
                    .setParameter("id", id)
                    .executeUpdate();
            em.remove(entity);
            var entry = toRegistryEntry(entity);
            var event = RegistryEvent.deregistered(entry);
            eventSink.accept(event);
            notifyWatchers(event);
        }
    }

    @Override
    public Optional<RegistryEntry> resolve(String id) {
        var entity = em.find(RegistryEntryEntity.class, id);
        return entity != null ? Optional.of(toRegistryEntry(entity)) : Optional.empty();
    }

    @Override
    public List<RegistryEntry> discover(RegistryQuery query) {
        var jpql = new StringBuilder("SELECT e FROM RegistryEntryEntity e WHERE e.tenancyId = :tenancyId");
        if (query.type() != null) jpql.append(" AND e.type = :type");
        if (query.namespace() != null) jpql.append(" AND e.namespace = :namespace");

        var q = em.createQuery(jpql.toString(), RegistryEntryEntity.class)
                .setParameter("tenancyId", query.tenancyId());
        if (query.type() != null) q.setParameter("type", query.type());
        if (query.namespace() != null) q.setParameter("namespace", query.namespace());

        return q.getResultList().stream().map(this::toRegistryEntry).toList();
    }

    @Override
    public void link(Relationship rel) {
        var entity = new RelationshipEntity();
        entity.sourceId = rel.sourceId();
        entity.targetId = rel.targetId();
        entity.type = rel.type();
        em.persist(entity);
        eventSink.accept(RegistryEvent.linked(rel));
    }

    @Override
    public void unlink(String sourceId, String targetId) {
        var matches = em.createQuery(
                                "SELECT r FROM RelationshipEntity r WHERE r.sourceId = :src AND r.targetId = :tgt",
                                RelationshipEntity.class)
                        .setParameter("src", sourceId)
                        .setParameter("tgt", targetId)
                        .getResultList();
        for (var entity : matches) {
            em.remove(entity);
            eventSink.accept(RegistryEvent.unlinked(new Relationship(entity.sourceId, entity.targetId, entity.type)));
        }
    }

    @Override
    public List<Relationship> relationships(String id) {
        return em.createQuery(
                        "SELECT r FROM RelationshipEntity r WHERE r.sourceId = :id OR r.targetId = :id",
                        RelationshipEntity.class)
                .setParameter("id", id)
                .getResultList()
                .stream()
                .map(r -> new Relationship(r.sourceId, r.targetId, r.type))
                .toList();
    }

    @Override
    public void watch(RegistryQuery query, Consumer<RegistryEvent> listener) {
        watchers.add(new WatchSubscription(query, listener));
    }

    @Override
    public void registerCascadeRule(CascadeRule rule) {
        var entity = em.find(CascadeRuleEntity.class, rule.relationshipType());
        if (entity == null) {
            entity = new CascadeRuleEntity();
            entity.relationshipType = rule.relationshipType();
        }
        entity.onSourceDeregister = rule.onSourceDeregister().name();
        em.merge(entity);
    }

    @Override
    public List<CascadeRule> cascadeRules() {
        return em.createQuery("SELECT c FROM CascadeRuleEntity c", CascadeRuleEntity.class)
                .getResultList()
                .stream()
                .map(c -> new CascadeRule(c.relationshipType, CascadeAction.valueOf(c.onSourceDeregister)))
                .toList();
    }

    public void checkHeartbeats() {
        var now = Instant.now();
        var entries = em.createQuery(
                        "SELECT e FROM RegistryEntryEntity e WHERE e.health <> :down", RegistryEntryEntity.class)
                .setParameter("down", HealthStatus.DOWN.name())
                .getResultList();
        for (var entity : entries) {
            var lastSeen = entity.lastHeartbeat != null ? entity.lastHeartbeat : entity.registeredAt;
            if (now.isAfter(lastSeen.plusSeconds(entity.ttlSeconds))) {
                entity.health = HealthStatus.DOWN.name();
                em.merge(entity);
                var entry = toRegistryEntry(entity);
                var event = RegistryEvent.healthChanged(entry);
                eventSink.accept(event);
                notifyWatchers(event);
            }
        }
    }

    private RegistryEntry toRegistryEntry(RegistryEntryEntity entity) {
        return new RegistryEntry(
                entity.id, entity.type, entity.namespace, entity.tenancyId,
                deserializeMetadata(entity.metadata),
                entity.registeredAt, entity.lastHeartbeat,
                Duration.ofSeconds(entity.ttlSeconds),
                HealthStatus.valueOf(entity.health));
    }

    private String serializeMetadata(Map<String, String> metadata) {
        try {
            return metadata.isEmpty() ? null : MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize metadata", e);
        }
    }

    private Map<String, String> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return Map.copyOf(MAPPER.readValue(json, MAP_TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize metadata", e);
        }
    }

    private void notifyWatchers(RegistryEvent event) {
        for (var sub : watchers) {
            if (matchesWatch(sub.query(), event)) {
                sub.listener().accept(event);
            }
        }
    }

    private boolean matchesWatch(RegistryQuery query, RegistryEvent event) {
        var entry = event.entry();
        if (entry == null) return false;
        if (!entry.tenancyId().equals(query.tenancyId())) return false;
        if (query.type() != null && !entry.type().equals(query.type())) return false;
        if (query.namespace() != null && !entry.namespace().equals(query.namespace())) return false;
        return true;
    }

    private record WatchSubscription(RegistryQuery query, Consumer<RegistryEvent> listener) {}
}
