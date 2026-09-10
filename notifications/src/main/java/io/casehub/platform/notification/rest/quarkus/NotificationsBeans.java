package io.casehub.platform.notification.rest.quarkus;

import io.casehub.platform.api.delivery.DeliveryChannelRegistry;
import io.casehub.platform.notification.PreferenceValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class NotificationsBeans {

    @Produces
    @ApplicationScoped
    public PreferenceValidator preferenceValidator(DeliveryChannelRegistry channelRegistry) {
        return new PreferenceValidator(channelRegistry);
    }
}
