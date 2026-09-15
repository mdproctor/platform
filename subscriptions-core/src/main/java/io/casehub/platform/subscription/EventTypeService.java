package io.casehub.platform.subscription;

import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.api.subscription.EventTypeRegistry;

import java.util.Set;

public class EventTypeService {

    private final EventTypeRegistry eventTypeRegistry;

    public EventTypeService(EventTypeRegistry eventTypeRegistry) {
        this.eventTypeRegistry = eventTypeRegistry;
    }

    public Set<EventTypeDescriptor> listEventTypes() {
        return eventTypeRegistry.discover();
    }
}
