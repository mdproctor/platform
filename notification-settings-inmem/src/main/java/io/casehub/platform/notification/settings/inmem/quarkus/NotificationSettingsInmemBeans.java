package io.casehub.platform.notification.settings.inmem.quarkus;

import io.casehub.platform.notification.settings.inmem.InMemoryNotificationPreferenceStore;
import io.casehub.platform.notification.settings.inmem.InMemorySuppressionStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class NotificationSettingsInmemBeans {

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public InMemoryNotificationPreferenceStore inMemoryNotificationPreferenceStore() {
        return new InMemoryNotificationPreferenceStore();
    }

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public InMemorySuppressionStore inMemorySuppressionStore() {
        return new InMemorySuppressionStore();
    }
}
