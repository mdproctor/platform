package io.casehub.platform.notification.settings.inmem;

import io.casehub.platform.api.notification.settings.NotificationPreferenceStore;
import io.casehub.platform.api.notification.settings.NotificationPreferenceUpdate;
import io.casehub.platform.api.notification.settings.NotificationPreferences;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryNotificationPreferenceStore implements NotificationPreferenceStore {

    private final ConcurrentHashMap<String, NotificationPreferences> store = new ConcurrentHashMap<>();

    @Override
    public Optional<NotificationPreferences> get(String userId, String tenancyId) {
        String key = makeKey(userId, tenancyId);
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public NotificationPreferences update(String userId, String tenancyId, NotificationPreferenceUpdate update) {
        String key = makeKey(userId, tenancyId);
        Instant now = Instant.now();

        return store.compute(key, (k, existing) -> {
            if (existing == null) {
                return new NotificationPreferences(
                        userId,
                        tenancyId,
                        update.channelDefaults() != null ? update.channelDefaults() : Map.of(),
                        update.clearQuietHours() ? null : update.quietHours(),
                        now
                );
            } else {
                return new NotificationPreferences(
                        userId,
                        tenancyId,
                        update.channelDefaults() != null ? update.channelDefaults() : existing.channelDefaults(),
                        update.clearQuietHours() ? null :
                                (update.quietHours() != null ? update.quietHours() : existing.quietHours()),
                        now
                );
            }
        });
    }

    private String makeKey(String userId, String tenancyId) {
        return userId + ":" + tenancyId;
    }

    public void clear() {
        store.clear();
    }
}
