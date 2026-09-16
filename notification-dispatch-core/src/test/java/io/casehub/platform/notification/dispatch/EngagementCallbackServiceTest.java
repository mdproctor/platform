package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryAttemptPage;
import io.casehub.platform.api.delivery.DeliveryAttemptQuery;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;
import io.casehub.platform.api.delivery.EngagementCallbackHandler;
import io.casehub.platform.api.delivery.EngagementEvent;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.delivery.RawEngagement;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.preferences.BooleanPreference;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.PlatformPreferenceKeys;
import io.casehub.platform.api.preferences.PreferenceProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngagementCallbackServiceTest {

    private final List<String> recorded = new ArrayList<>();

    private final DeliveryAttempt testAttempt = new DeliveryAttempt(
            "att-1", "src-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "t1",
            DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
            Instant.now(), Instant.now(), Instant.now(), null, null, "{}",
            null, null);

    private final DeliveryAttemptStore store = new DeliveryAttemptStore() {
        @Override public void store(DeliveryAttempt a) {}
        @Override public void update(DeliveryAttempt a) {}
        @Override public DeliveryAttempt findById(String id) { return "att-1".equals(id) ? testAttempt : null; }
        @Override public DeliveryAttempt findById(String id, String tenancyId) { return findById(id); }
        @Override public List<DeliveryAttempt> claimRetryable(Instant now, int batchSize) { return List.of(); }
        @Override public DeliveryAttemptPage find(DeliveryAttemptQuery q) { return new DeliveryAttemptPage(List.of(), null); }
        @Override public List<DeliveryAttempt> findBySource(String sourceId, DeliverySourceType st, String tenancyId) { return List.of(); }
        @Override public void recordEngagement(EngagementEvent e) { recorded.add(e.attemptId()); }
        @Override public List<EngagementEvent> findEngagementsByAttemptId(String id, String tenancyId) { return List.of(); }
        @Override public List<EngagementEvent> findEngagementsBySource(String sourceId, DeliverySourceType st, String tenancyId) { return List.of(); }
    };

    private final EngagementRecorder recorder = new EngagementRecorder(store, null, enabledProvider());

    private final CurrentPrincipal principal = new CurrentPrincipal() {
        @Override public String actorId() { return "user-1"; }
        @Override public String tenancyId() { return "t1"; }
        @Override public Set<String> groups() { return Set.of(); }
        @Override public boolean isCrossTenantAdmin() { return false; }
    };

    private final EngagementCallbackHandler emailHandler = new EngagementCallbackHandler() {
        @Override public String channelId() { return "email"; }
        @Override public List<RawEngagement> translate(String rawPayload, Map<String, String> headers) {
            return List.of(new RawEngagement("att-1", EngagementType.OPENED, null));
        }
    };

    @Test
    void handleCallback_routes_to_handler() {
        var service = new EngagementCallbackService(
                store, recorder, principal, Map.of("email", emailHandler), enabledProvider());

        service.handleCallback("email", "{}", Map.of());
        assertThat(recorded).isNotEmpty();
    }

    @Test
    void handleCallback_unknown_channel_throws() {
        var service = new EngagementCallbackService(
                store, recorder, principal, Map.of(), enabledProvider());

        assertThatThrownBy(() -> service.handleCallback("unknown", "{}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handleCallback_disabled_throws() {
        var service = new EngagementCallbackService(
                store, recorder, principal, Map.of(), disabledProvider());

        assertThatThrownBy(() -> service.handleCallback("email", "{}", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordDirect_records_engagement() {
        var service = new EngagementCallbackService(
                store, recorder, principal, Map.of(), enabledProvider());

        service.recordDirect("att-1", new DirectEngagementRequest(EngagementType.CLICKED, null));
        assertThat(recorded).isNotEmpty();
    }

    @Test
    void recordDirect_null_type_throws() {
        var service = new EngagementCallbackService(
                store, recorder, principal, Map.of(), enabledProvider());

        assertThatThrownBy(() -> service.recordDirect("att-1", new DirectEngagementRequest(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static PreferenceProvider enabledProvider() {
        return scope -> new MapPreferences(Map.of(
                PlatformPreferenceKeys.ENGAGEMENT_ENABLED.qualifiedName(),
                (Object) BooleanPreference.of(true).toSerializedValue()));
    }

    @SuppressWarnings("unchecked")
    private static PreferenceProvider disabledProvider() {
        return scope -> new MapPreferences(Map.of(
                PlatformPreferenceKeys.ENGAGEMENT_ENABLED.qualifiedName(),
                (Object) BooleanPreference.of(false).toSerializedValue()));
    }
}
