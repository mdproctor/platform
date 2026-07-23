package io.casehub.platform.notification.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.notification.AllNotificationsRead;
import io.casehub.platform.api.notification.NotificationCreated;
import io.casehub.platform.api.notification.NotificationStatusChanged;
import io.casehub.platform.api.notification.NotificationStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
@Path("/notifications/stream")
public class NotificationSseResource {

    private static final Logger          LOG              = Logger.getLogger(NotificationSseResource.class);
    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ConcurrentHashMap<String, Set<EmitterWithContext>> connections = new ConcurrentHashMap<>();
    private final NotificationStore                                  store;
    private final CurrentPrincipal                                   principal;
    private final ObjectMapper                                       objectMapper;

    @Inject
    public NotificationSseResource(
            NotificationStore store,
            CurrentPrincipal principal,
            ObjectMapper objectMapper) {
        this.store        = store;
        this.principal    = principal;
        this.objectMapper = objectMapper;
    }

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@Context SseEventSink eventSink, @Context Sse sse) {
        String userId    = principal.actorId();
        String tenancyId = principal.tenancyId();
        String key       = tenancyId + "::" + userId;

        var emitterWithContext = new EmitterWithContext(eventSink, sse, userId, tenancyId);

        connections.computeIfAbsent(key, k ->
                                                 Collections.newSetFromMap(new ConcurrentHashMap<>())
                                   ).add(emitterWithContext);

        VIRTUAL_EXECUTOR.execute(() -> {
            try {
                long count = store.unreadCount(userId, tenancyId);
                sendUnreadCount(eventSink, sse, count);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to fetch initial unread count for user %s", userId);
            }
        });
    }

    void onNotificationCreated(@ObservesAsync NotificationCreated event) {
        var    notification = event.notification();
        String key          = notification.tenancyId() + "::" + notification.userId();

        var emitters = connections.get(key);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(notification);
        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to serialize notification %s", notification.id());
            return;
        }

        for (var emitter : emitters) {
            if (!emitter.eventSink().isClosed()) {
                try {
                    var sseEvent = emitter.sse().newEventBuilder()
                                          .name("notification")
                                          .data(json)
                                          .build();
                    emitter.eventSink().send(sseEvent);
                } catch (Exception e) {
                    LOG.debugf(e, "Failed to send notification to user %s", notification.userId());
                    removeEmitter(key, emitter);
                }
            } else {
                removeEmitter(key, emitter);
            }
        }

        try {
            long count = store.unreadCount(notification.userId(), notification.tenancyId());
            sendUnreadCountToUser(notification.userId(), notification.tenancyId(), count);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch unread count for user %s", notification.userId());
        }
    }

    void onNotificationStatusChanged(@ObservesAsync NotificationStatusChanged event) {
        var    notification = event.notification();
        String key          = notification.tenancyId() + "::" + notification.userId();

        var emitters = connections.get(key);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(notification);
        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to serialize notification %s", notification.id());
            return;
        }

        for (var emitter : emitters) {
            if (!emitter.eventSink().isClosed()) {
                try {
                    var sseEvent = emitter.sse().newEventBuilder()
                                          .name("notification-updated")
                                          .data(json)
                                          .build();
                    emitter.eventSink().send(sseEvent);
                } catch (Exception e) {
                    LOG.debugf(e, "Failed to send updated notification to user %s", notification.userId());
                    removeEmitter(key, emitter);
                }
            } else {
                removeEmitter(key, emitter);
            }
        }

        try {
            long count = store.unreadCount(notification.userId(), notification.tenancyId());
            sendUnreadCountToUser(notification.userId(), notification.tenancyId(), count);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch unread count for user %s", notification.userId());
        }
    }

    void onAllNotificationsRead(@ObservesAsync AllNotificationsRead event) {
        try {
            long count = store.unreadCount(event.userId(), event.tenancyId());
            sendUnreadCountToUser(event.userId(), event.tenancyId(), count);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch unread count for user %s", event.userId());
        }
    }

    private void sendUnreadCountToUser(String userId, String tenancyId, long count) {
        String key      = tenancyId + "::" + userId;
        var    emitters = connections.get(key);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (var emitter : emitters) {
            if (!emitter.eventSink().isClosed()) {
                try {
                    var event = emitter.sse().newEventBuilder()
                                       .name("unread-count")
                                       .data("{\"count\":" + count + "}")
                                       .build();
                    emitter.eventSink().send(event);
                } catch (Exception e) {
                    LOG.debugf(e, "Failed to send unread count to user %s", userId);
                    removeEmitter(key, emitter);
                }
            } else {
                removeEmitter(key, emitter);
            }
        }
    }

    private void sendUnreadCount(SseEventSink eventSink, Sse sse, long count) {
        try {
            var event = sse.newEventBuilder()
                           .name("unread-count")
                           .data("{\"count\":" + count + "}")
                           .build();
            eventSink.send(event);
        } catch (Exception e) {
            LOG.debug("Failed to send initial unread count", e);
        }
    }

    @Scheduled(every = "60s")
    void sweepStaleEmitters() {
        connections.forEach((key, emitters) ->
                                    emitters.removeIf(e -> e.eventSink().isClosed()));
        connections.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private void removeEmitter(String connectionKey, EmitterWithContext emitter) {
        connections.computeIfPresent(connectionKey, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private record EmitterWithContext(
            SseEventSink eventSink,
            Sse sse,
            String userId,
            String tenancyId
    ) {}
}
