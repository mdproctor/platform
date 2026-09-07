package io.casehub.platform.delivery.tracking.inmem.quarkus;

import io.casehub.platform.delivery.tracking.inmem.InMemoryDeliveryAttemptStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DeliveryTrackingInmemBeans {

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public InMemoryDeliveryAttemptStore inMemoryDeliveryAttemptStore(
            @ConfigProperty(name = "casehub.delivery.tracking.inmem.max-size", defaultValue = "10000") int maxSize) {
        return new InMemoryDeliveryAttemptStore(maxSize);
    }
}
