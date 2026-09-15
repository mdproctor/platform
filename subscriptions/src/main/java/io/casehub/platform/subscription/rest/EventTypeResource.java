package io.casehub.platform.subscription.rest;

import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.subscription.EventTypeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import java.util.Set;

@ApplicationScoped
@Path("/subscriptions/event-types")
public class EventTypeResource {

    private final EventTypeService eventTypeService;

    @Inject
    public EventTypeResource(final EventTypeService eventTypeService) {
        this.eventTypeService = eventTypeService;
    }

    @GET
    public Set<EventTypeDescriptor> listEventTypes() {
        return eventTypeService.listEventTypes();
    }
}
