package io.casehub.platform.delivery.channel.inmem.quarkus;

import io.casehub.platform.delivery.channel.inmem.InMemoryDeliveryChannelRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class DeliveryChannelInmemBeans {

    @Produces
    @ApplicationScoped
    public InMemoryDeliveryChannelRegistry inMemoryDeliveryChannelRegistry() {
        return new InMemoryDeliveryChannelRegistry();
    }
}
