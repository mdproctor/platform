package io.casehub.platform.registry;

import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryEvent;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.api.registry.Relationship;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class NoOpRegistryService implements RegistryService {
    @Override public void register(RegistryEntry entry) {}
    @Override public void heartbeat(String id) {}
    @Override public void deregister(String id) {}
    @Override public Optional<RegistryEntry> resolve(String id) { return Optional.empty(); }
    @Override public List<RegistryEntry> discover(RegistryQuery query) { return List.of(); }
    @Override public void link(Relationship rel) {}
    @Override public void unlink(String sourceId, String targetId) {}
    @Override public List<Relationship> relationships(String id) { return List.of(); }
    @Override public void watch(RegistryQuery query, Consumer<RegistryEvent> listener) {}
}
