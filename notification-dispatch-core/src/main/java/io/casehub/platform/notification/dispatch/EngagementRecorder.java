package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.EngagementEvent;
import io.casehub.platform.api.delivery.EngagementRecorded;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.util.UUIDv7;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.preferences.PlatformPreferenceKeys;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.function.Consumer;

public class EngagementRecorder {

    private static final Logger LOG = Logger.getLogger(EngagementRecorder.class);

    private final DeliveryAttemptStore store;
    private final Consumer<EngagementRecorded> engagementCallback;
    private final PreferenceProvider preferenceProvider;

    public EngagementRecorder(DeliveryAttemptStore store,
                              Consumer<EngagementRecorded> engagementCallback,
                              PreferenceProvider preferenceProvider) {
        this.store = store;
        this.engagementCallback = engagementCallback;
        this.preferenceProvider = preferenceProvider;
    }

    public void record(DeliveryAttempt attempt, EngagementType type, String metadata) {
        boolean enabled = preferenceProvider
                .resolve(SettingsScope.root(TenancyConstants.PLATFORM_TENANT_ID))
                .getOrDefault(PlatformPreferenceKeys.ENGAGEMENT_ENABLED)
                .value();
        if (!enabled) {
            return;
        }
        var event = new EngagementEvent(
                UUIDv7.generate(),
                attempt.id(),
                attempt.sourceId(),
                attempt.sourceType(),
                attempt.channelId(),
                attempt.userId(),
                attempt.tenancyId(),
                type,
                Instant.now(),
                metadata);
        try {
            store.recordEngagement(event);
            if (engagementCallback != null) {
                engagementCallback.accept(new EngagementRecorded(event));
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to record engagement for attempt '%s'", attempt.id());
        }
    }
}
