package io.casehub.platform.notification;

import io.casehub.platform.api.delivery.DeliveryChannelDescriptor;
import io.casehub.platform.api.delivery.DeliveryChannelRegistry;
import io.casehub.platform.api.notification.settings.ChannelPreference;
import io.casehub.platform.api.notification.settings.NotificationPreferenceUpdate;
import io.casehub.platform.api.notification.settings.NotificationPreferences;
import io.casehub.platform.api.notification.settings.QuietHoursAction;

import java.util.Map;

public class PreferenceValidator {

    private final DeliveryChannelRegistry channelRegistry;

    public PreferenceValidator(DeliveryChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    public void validate(NotificationPreferenceUpdate update, NotificationPreferences existing) {
        var effectiveQuietHours = update.quietHours() != null ? update.quietHours()
                : (existing != null ? existing.quietHours() : null);

        if (effectiveQuietHours == null
                || effectiveQuietHours.action() != QuietHoursAction.BUFFER_FOR_DIGEST) {
            return;
        }

        Map<String, ChannelPreference> effectiveChannels = update.channelDefaults() != null
                ? update.channelDefaults()
                : (existing != null ? existing.channelDefaults() : Map.of());

        boolean anyDigested = false;
        for (var entry : effectiveChannels.entrySet()) {
            if (entry.getValue().isDigested()) {
                anyDigested = true;
                break;
            }
        }

        if (!anyDigested) {
            for (DeliveryChannelDescriptor descriptor : channelRegistry.discover()) {
                if (!effectiveChannels.containsKey(descriptor.channelId())
                        && descriptor.defaultDigestSchedule() != null) {
                    anyDigested = true;
                    break;
                }
            }
        }

        if (!anyDigested) {
            throw new IllegalArgumentException(
                    "BUFFER_FOR_DIGEST requires at least one channel with a digest schedule");
        }
    }
}
