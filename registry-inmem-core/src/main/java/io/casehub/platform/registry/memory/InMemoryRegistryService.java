package io.casehub.platform.registry.memory;

import io.casehub.platform.api.registry.CascadeRule;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryEvent;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.api.registry.Relationship;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class InMemoryRegistryService implements RegistryService {

    private final ConcurrentHashMap<String, RegistryEntry> entries = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Relationship> relationships = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<WatchSubscription> watchers = new CopyOnWriteArrayList<>();
    private final Consumer<RegistryEvent> cdiEventSink;
    private final ConcurrentHashMap<String, CascadeRule> cascadeRules = new ConcurrentHashMap<>();


    public InMemoryRegistryService(Consumer<RegistryEvent> cdiEventSink) {
        this.cdiEventSink = Objects.requireNonNull(cdiEventSink);
    }

    @Override
    public void register(RegistryEntry entry) {
        entries.put(entry.id(), entry);
        var event = RegistryEvent.registered(entry);
        cdiEventSink.accept(event);
        notifyWatchers(event);
    }

    @Override
    public void heartbeat(String id) {
        entries.computeIfPresent(id, (k, e) -> e.withHeartbeat(Instant.now()));
    }

    @Override
    public void deregister(String id) {
        var removed = entries.remove(id);
        if (removed != null) {
            relationships.removeIf(r ->
                    r.sourceId().equals(id) || r.targetId().equals(id));
            var event = RegistryEvent.deregistered(removed);
            cdiEventSink.accept(event);
            notifyWatchers(event);
        }
    }

    @Override
    public Optional<RegistryEntry> resolve(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<RegistryEntry> discover(RegistryQuery query) {
        return entries.values().stream()
                .filter(e -> e.tenancyId().equals(query.tenancyId()))
                .filter(e -> query.type() == null || e.type().equals(query.type()))
                .filter(e -> query.namespace() == null || e.namespace().equals(query.namespace()))
                .toList();
    }

    @Override
    public void link(Relationship rel) {
        relationships.add(rel);
        cdiEventSink.accept(RegistryEvent.linked(rel));
    }

    @Override
    public void unlink(String sourceId, String targetId) {
        relationships.removeIf(r ->
                r.sourceId().equals(sourceId) && r.targetId().equals(targetId));
    }

    @Override
    public List<Relationship> relationships(String id) {
        return relationships.stream()
                .filter(r -> r.sourceId().equals(id) || r.targetId().equals(id))
                .toList();
    }

    @Override
    public void watch(RegistryQuery query, Consumer<RegistryEvent> listener) {
        watchers.add(new WatchSubscription(query, listener));
    }

    @Override
    public void registerCascadeRule(CascadeRule rule) {
        cascadeRules.put(rule.relationshipType(), rule);
    }

    @Override
    public List<CascadeRule> cascadeRules() {
        return List.copyOf(cascadeRules.values());
    }


    List<RegistryEntry> allEntries() {
        return List.copyOf(entries.values());
    }

    void updateEntry(RegistryEntry entry) {
        var previous = entries.put(entry.id(), entry);
        if (previous != null && previous.health() != entry.health()) {
            var event = RegistryEvent.healthChanged(entry);
            cdiEventSink.accept(event);
            notifyWatchers(event);
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
