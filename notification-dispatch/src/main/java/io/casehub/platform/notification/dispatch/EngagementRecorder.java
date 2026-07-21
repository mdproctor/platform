package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.EngagementEvent;
import io.casehub.platform.api.delivery.EngagementRecorded;
import io.casehub.platform.api.delivery.EngagementType;
import io.casehub.platform.api.util.UUIDv7;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;

@ApplicationScoped
public class EngagementRecorder {

    private static final Logger LOG = Logger.getLogger(EngagementRecorder.class);

    private final DeliveryAttemptStore store;
    private final Event<EngagementRecorded> engagementEvent;
    private final boolean enabled;

    @Inject
    public EngagementRecorder(DeliveryAttemptStore store,
                              Event<EngagementRecorded> engagementEvent,
                              @ConfigProperty(name = "casehub.delivery.engagement.enabled", defaultValue = "false")
                              boolean enabled) {
        this.store = store;
        this.engagementEvent = engagementEvent;
        this.enabled = enabled;
    }

    public void record(DeliveryAttempt attempt, EngagementType type, String metadata) {
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
            engagementEvent.fireAsync(new EngagementRecorded(event));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to record engagement for attempt '%s'", attempt.id());
        }
    }
}
