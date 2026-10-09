package io.casehub.platform.registry.memory.quarkus;

import io.casehub.platform.api.registry.RegistryEvent;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class RegistryInmemBeans {

    @Inject
    Event<RegistryEvent> registryEvent;

    @Produces
    @Alternative
    @Priority(50)
    @ApplicationScoped
    public InMemoryRegistryService inMemoryRegistryService() {
        return new InMemoryRegistryService(e -> registryEvent.fireAsync(e));
    }
}
