package io.casehub.platform.subscription;

import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.api.subscription.EventTypeRegistry;

import java.util.Optional;
import java.util.Set;

public class NoOpEventTypeRegistry implements EventTypeRegistry {
    @Override public void register(final EventTypeDescriptor descriptor) {}
    @Override public Optional<EventTypeDescriptor> resolve(final String eventType) { return Optional.empty(); }
    @Override public Set<EventTypeDescriptor> discover() { return Set.of(); }
}
