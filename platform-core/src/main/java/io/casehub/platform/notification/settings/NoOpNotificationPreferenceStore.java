package io.casehub.platform.notification.settings;

import io.casehub.platform.api.notification.settings.NotificationPreferenceStore;
import io.casehub.platform.api.notification.settings.NotificationPreferenceUpdate;
import io.casehub.platform.api.notification.settings.NotificationPreferences;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public class NoOpNotificationPreferenceStore implements NotificationPreferenceStore {
    @Override public Optional<NotificationPreferences> get(final String userId, final String tenancyId) { return Optional.empty(); }
    @Override public NotificationPreferences update(final String userId, final String tenancyId, final NotificationPreferenceUpdate update) {
        return new NotificationPreferences(userId, tenancyId, Map.of(), null, Instant.now());
    }
}
