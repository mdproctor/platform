package io.casehub.platform.endpoints.memory.quarkus;

import io.casehub.platform.api.endpoints.EndpointRegistered;
import io.casehub.platform.endpoints.memory.InMemoryEndpointRegistry;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class EndpointsMemoryBeans {

    @Inject Event<EndpointRegistered> registeredEvent;

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public InMemoryEndpointRegistry inMemoryEndpointRegistry() {
        return new InMemoryEndpointRegistry(e -> registeredEvent.fireAsync(e));
    }
}
