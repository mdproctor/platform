package io.casehub.platform.subscription;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.api.subscription.EventTypeRegistry;

import java.util.Set;

@McpDomain(value = "subscription-event-types", basePath = "/subscriptions/event-types")
public class EventTypeService {

    private final EventTypeRegistry eventTypeRegistry;

    public EventTypeService(EventTypeRegistry eventTypeRegistry) {
        this.eventTypeRegistry = eventTypeRegistry;
    }

    @PlatformQuery("List available subscription event types")
    public Set<EventTypeDescriptor> listEventTypes() {
        return eventTypeRegistry.discover();
    }
}
