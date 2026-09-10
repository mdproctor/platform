package io.casehub.platform.subscription.quarkus;

import io.casehub.platform.subscription.InMemoryEventTypeRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class SubscriptionsBeans {

    @Produces
    @ApplicationScoped
    public InMemoryEventTypeRegistry inMemoryEventTypeRegistry() {
        return new InMemoryEventTypeRegistry();
    }
}
