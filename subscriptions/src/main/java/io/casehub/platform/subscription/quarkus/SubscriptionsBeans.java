package io.casehub.platform.subscription.quarkus;

import io.casehub.platform.api.expression.ExpressionEngineRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.subscription.EventTypeRegistry;
import io.casehub.platform.api.subscription.SubscriptionStore;
import io.casehub.platform.subscription.EventTypeService;
import io.casehub.platform.subscription.InMemoryEventTypeRegistry;
import io.casehub.platform.subscription.SubscriptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class SubscriptionsBeans {

    @Produces
    @ApplicationScoped
    public InMemoryEventTypeRegistry inMemoryEventTypeRegistry() {
        return new InMemoryEventTypeRegistry();
    }

    @Produces
    @ApplicationScoped
    public EventTypeService eventTypeService(EventTypeRegistry registry) {
        return new EventTypeService(registry);
    }

    @Produces
    @ApplicationScoped
    public SubscriptionService subscriptionService(SubscriptionStore store,
                                                    CurrentPrincipal principal,
                                                    ExpressionEngineRegistry expressionRegistry) {
        return new SubscriptionService(store, principal, expressionRegistry);
    }
}
