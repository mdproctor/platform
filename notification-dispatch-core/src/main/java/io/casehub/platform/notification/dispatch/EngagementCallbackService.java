package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.EngagementCallbackHandler;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.preferences.PlatformPreferenceKeys;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;

import java.util.Map;

public class EngagementCallbackService {

    private final DeliveryAttemptStore store;
    private final EngagementRecorder recorder;
    private final CurrentPrincipal principal;
    private final Map<String, EngagementCallbackHandler> handlers;
    private final PreferenceProvider preferenceProvider;

    public EngagementCallbackService(DeliveryAttemptStore store,
                                     EngagementRecorder recorder,
                                     CurrentPrincipal principal,
                                     Map<String, EngagementCallbackHandler> handlers,
                                     PreferenceProvider preferenceProvider) {
        this.store = store;
        this.recorder = recorder;
        this.principal = principal;
        this.handlers = handlers;
        this.preferenceProvider = preferenceProvider;
    }

    public void handleCallback(String channelId, String rawPayload, Map<String, String> headers) {
        if (!isEngagementEnabled()) {
            throw new IllegalStateException("Engagement tracking is disabled");
        }
        var handler = handlers.get(channelId);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown channel: " + channelId);
        }
        var rawEvents = handler.translate(rawPayload, headers);
        if (rawEvents != null) {
            for (var raw : rawEvents) {
                DeliveryAttempt attempt = store.findById(raw.attemptId());
                if (attempt != null) {
                    recorder.record(attempt, raw.type(), raw.metadata());
                }
            }
        }
    }

    public void recordDirect(String attemptId, EngagementType type, String metadata) {
        if (!isEngagementEnabled()) {
            throw new IllegalStateException("Engagement tracking is disabled");
        }
        if (type == null) {
            throw new IllegalArgumentException("Engagement type is required");
        }
        DeliveryAttempt attempt = store.findById(attemptId, principal.tenancyId());
        if (attempt == null) {
            throw new IllegalArgumentException("Attempt not found: " + attemptId);
        }
        recorder.record(attempt, type, metadata);
    }

    private boolean isEngagementEnabled() {
        return preferenceProvider
                .resolve(SettingsScope.root(TenancyConstants.PLATFORM_TENANT_ID))
                .getOrDefault(PlatformPreferenceKeys.ENGAGEMENT_ENABLED)
                .value();
    }
}
