package io.casehub.platform.subscription;

import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.api.subscription.EventTypeRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryEventTypeRegistry implements EventTypeRegistry {

    private final ConcurrentHashMap<String, EventTypeDescriptor> store = new ConcurrentHashMap<>();

    @Override
    public void register(final EventTypeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        store.put(descriptor.eventType(), descriptor);
    }

    @Override
    public Optional<EventTypeDescriptor> resolve(final String eventType) {
        Objects.requireNonNull(eventType, "eventType");
        return Optional.ofNullable(store.get(eventType));
    }

    @Override
    public Set<EventTypeDescriptor> discover() {
        return store.values().stream().collect(Collectors.toUnmodifiableSet());
    }
}
