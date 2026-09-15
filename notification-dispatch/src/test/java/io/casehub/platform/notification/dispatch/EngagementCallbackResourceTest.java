package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.preferences.BooleanPreference;
import io.casehub.platform.api.preferences.PlatformPreferenceKeys;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;
import io.casehub.platform.api.delivery.EngagementCallbackHandler;
import io.casehub.platform.api.delivery.EngagementRecorded;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.delivery.RawEngagement;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.util.UUIDv7;
import io.casehub.platform.delivery.tracking.inmem.InMemoryDeliveryAttemptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EngagementCallbackResourceTest {

    private InMemoryDeliveryAttemptStore store;
    private EngagementRecorder recorder;
    private List<EngagementRecorded> firedEvents;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryAttemptStore(10000);
        firedEvents = new ArrayList<>();
        var enabledProvider = providerWith(
                PlatformPreferenceKeys.ENGAGEMENT_ENABLED, BooleanPreference.of(true));
        recorder = new EngagementRecorder(store, firedEvents::add, enabledProvider);
    }

    @Test
    void directPathRecordsEngagement() {
        var attempt = deliveredAttempt();
        store.store(attempt);
        var resource = createResource(fixedPrincipal("tenant-1"), Map.of(), enabledProvider());

        var response = resource.recordDirect(attempt.id(),
                new DirectEngagementRequest(EngagementType.OPENED, null));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(store.findEngagementsByAttemptId(attempt.id(), "tenant-1")).hasSize(1);
    }

    @Test
    void directPathReturns404ForMissingAttempt() {
        var resource = createResource(fixedPrincipal("tenant-1"), Map.of(), enabledProvider());

        var response = resource.recordDirect("nonexistent",
                new DirectEngagementRequest(EngagementType.OPENED, null));
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void directPathReturns404ForTenantMismatch() {
        var attempt = deliveredAttempt();
        store.store(attempt);
        var resource = createResource(fixedPrincipal("other-tenant"), Map.of(), enabledProvider());

        var response = resource.recordDirect(attempt.id(),
                new DirectEngagementRequest(EngagementType.OPENED, null));
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void callbackPathDelegatesToHandler() {
        var attempt = deliveredAttempt();
        store.store(attempt);
        var handler = new TestCallbackHandler(attempt.id());
        var resource = createResource(fixedPrincipal("tenant-1"), Map.of("email", handler), enabledProvider());

        var response = resource.handleCallback("email", "{\"event\":\"open\"}");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(store.findEngagementsByAttemptId(attempt.id(), "tenant-1")).hasSize(1);
    }

    @Test
    void callbackPathReturns404ForUnknownChannel() {
        var resource = createResource(fixedPrincipal("tenant-1"), Map.of(), enabledProvider());

        var response = resource.handleCallback("unknown", "{}");
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void callbackPathSkipsNonexistentAttempts() {
        var handler = new TestCallbackHandler("nonexistent-attempt");
        var resource = createResource(fixedPrincipal("tenant-1"), Map.of("email", handler), enabledProvider());

        var response = resource.handleCallback("email", "{}");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void returns404WhenDisabled() {
        var attempt = deliveredAttempt();
        store.store(attempt);
        var resource = createResource(fixedPrincipal("tenant-1"), Map.of(), disabledProvider());

        var directResponse = resource.recordDirect(attempt.id(),
                new DirectEngagementRequest(EngagementType.OPENED, null));
        assertThat(directResponse.getStatus()).isEqualTo(404);

        var callbackResponse = resource.handleCallback("email", "{}");
        assertThat(callbackResponse.getStatus()).isEqualTo(404);
    }

    @Test
    void callbackPathReturns200WhenTranslateThrows() {
        EngagementCallbackHandler handler = new EngagementCallbackHandler() {
            @Override public String channelId() { return "email"; }
            @Override public List<RawEngagement> translate(String rawPayload, Map<String, String> headers) {
                throw new RuntimeException("Bad payload");
            }
        };
        var resource = createResource(fixedPrincipal("tenant-1"), Map.of("email", handler), enabledProvider());
        var response = resource.handleCallback("email", "bad-payload");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void callbackPathReturns200WhenTranslateReturnsNull() {
        EngagementCallbackHandler handler = new EngagementCallbackHandler() {
            @Override public String channelId() { return "email"; }
            @Override public List<RawEngagement> translate(String rawPayload, Map<String, String> headers) {
                return null;
            }
        };
        var resource = createResource(fixedPrincipal("tenant-1"), Map.of("email", handler), enabledProvider());
        var response = resource.handleCallback("email", "{}");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void callbackPathReturns401WhenTranslateThrowsSecurityException() {
        EngagementCallbackHandler handler = new EngagementCallbackHandler() {
            @Override
            public String channelId() {return "email";}

            @Override
            public List<RawEngagement> translate(String rawPayload, Map<String, String> headers) {
                throw new SecurityException("Invalid HMAC signature");
            }
        };
        var resource = createResource(fixedPrincipal("tenant-1"), Map.of("email", handler), enabledProvider());
        var response = resource.handleCallback("email", "forged-payload");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(firedEvents).isEmpty();
    }


    @Test
    void directPathReturns400ForNullType() {
        var attempt = deliveredAttempt();
        store.store(attempt);
        var resource = createResource(fixedPrincipal("tenant-1"), Map.of(), enabledProvider());
        var response = resource.recordDirect(attempt.id(),
                new DirectEngagementRequest(null, null));
        assertThat(response.getStatus()).isEqualTo(400);
    }

    private DeliveryAttempt deliveredAttempt() {
        return new DeliveryAttempt(
                UUIDv7.generate(), "notif-1", DeliverySourceType.NOTIFICATION, "email", "user-1", "tenant-1",
                DeliveryType.IMMEDIATE, DeliveryStatus.DELIVERED, 1,
                Instant.now(), Instant.now(), Instant.now(), null, null, "{}",
                null, null);
    }

    private static PreferenceProvider enabledProvider() {
        return providerWith(PlatformPreferenceKeys.ENGAGEMENT_ENABLED, BooleanPreference.of(true));
    }

    private static PreferenceProvider disabledProvider() {
        return providerWith(PlatformPreferenceKeys.ENGAGEMENT_ENABLED, BooleanPreference.of(false));
    }

    private static PreferenceProvider providerWith(io.casehub.platform.api.preferences.PreferenceKey<?> key,
                                                    io.casehub.platform.api.preferences.SingleValuePreference value) {
        return scope -> new io.casehub.platform.api.preferences.MapPreferences(Map.of(key.qualifiedName(), value));
    }

    private CurrentPrincipal fixedPrincipal(String tenancyId) {
        return new CurrentPrincipal() {
            @Override public String actorId() { return "actor-1"; }
            @Override public String tenancyId() { return tenancyId; }
            @Override public java.util.Set<String> groups() { return java.util.Set.of(); }
            @Override public boolean isCrossTenantAdmin() { return false; }
        };
    }

    private EngagementCallbackResource createResource(CurrentPrincipal principal,
                                                         Map<String, EngagementCallbackHandler> handlers,
                                                         PreferenceProvider prefs) {
        var service = new EngagementCallbackService(store, recorder, principal, handlers, prefs);
        return new EngagementCallbackResource(service, null);
    }

    private static class TestCallbackHandler implements EngagementCallbackHandler {
        private final String attemptId;
        TestCallbackHandler(String attemptId) { this.attemptId = attemptId; }
        @Override public String channelId() { return "email"; }
        @Override public List<RawEngagement> translate(String rawPayload, Map<String, String> headers) {
            return List.of(new RawEngagement(attemptId, EngagementType.OPENED, null));
        }
    }
}
