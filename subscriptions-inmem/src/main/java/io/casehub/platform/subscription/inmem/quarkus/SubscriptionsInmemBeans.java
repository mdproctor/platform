package io.casehub.platform.subscription.inmem.quarkus;

import io.casehub.platform.api.subscription.SubscriptionCreated;
import io.casehub.platform.api.subscription.SubscriptionDeleted;
import io.casehub.platform.api.subscription.SubscriptionUpdated;
import io.casehub.platform.subscription.inmem.InMemorySubscriptionStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class SubscriptionsInmemBeans {

    @Inject Event<SubscriptionCreated> createdEvent;
    @Inject Event<SubscriptionUpdated> updatedEvent;
    @Inject Event<SubscriptionDeleted> deletedEvent;

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public InMemorySubscriptionStore inMemorySubscriptionStore() {
        return new InMemorySubscriptionStore(
                e -> createdEvent.fireAsync(e),
                e -> updatedEvent.fireAsync(e),
                e -> deletedEvent.fireAsync(e));
    }
}
