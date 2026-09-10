package io.casehub.platform.notification.inmem.quarkus;

import io.casehub.platform.api.notification.AllNotificationsRead;
import io.casehub.platform.api.notification.NotificationCreated;
import io.casehub.platform.api.notification.NotificationStatusChanged;
import io.casehub.platform.notification.inmem.InMemoryNotificationStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class NotificationsInmemBeans {

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public InMemoryNotificationStore inMemoryNotificationStore(
            @ConfigProperty(name = "casehub.notification.inmem.max-size", defaultValue = "10000") int maxSize,
            Event<NotificationCreated> createdEvent,
            Event<NotificationStatusChanged> statusChangedEvent,
            Event<AllNotificationsRead> allReadEvent) {
        return new InMemoryNotificationStore(
                maxSize,
                createdEvent::fireAsync,
                statusChangedEvent::fireAsync,
                allReadEvent::fireAsync);
    }
}
