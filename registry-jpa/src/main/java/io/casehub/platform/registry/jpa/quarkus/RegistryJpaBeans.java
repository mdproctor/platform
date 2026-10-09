package io.casehub.platform.registry.jpa.quarkus;

import io.casehub.platform.api.registry.RegistryEvent;
import io.casehub.platform.registry.jpa.JpaRegistryService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class RegistryJpaBeans {

    @Inject
    EntityManager em;

    @Inject
    Event<RegistryEvent> registryEvent;

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public JpaRegistryService jpaRegistryService() {
        return new JpaRegistryService(em, e -> registryEvent.fireAsync(e));
    }
}
