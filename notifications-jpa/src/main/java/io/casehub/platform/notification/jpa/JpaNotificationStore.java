package io.casehub.platform.notification.jpa;

import io.casehub.platform.api.notification.AllNotificationsRead;
import io.casehub.platform.api.notification.Notification;
import io.casehub.platform.api.notification.NotificationCreated;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationPage;
import io.casehub.platform.api.notification.NotificationQuery;
import io.casehub.platform.api.notification.NotificationStatus;
import io.casehub.platform.api.notification.NotificationStatusChanged;
import io.casehub.platform.api.notification.NotificationStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class JpaNotificationStore implements NotificationStore {

    @Inject
    EntityManager em;

    @Inject
    Event<NotificationCreated> createdEvent;

    @Inject
    Event<NotificationStatusChanged> statusChangedEvent;

    @Inject
    Event<AllNotificationsRead> allReadEvent;

    @Override
    @Transactional
    public Notification store(NotificationInput input) {
        NotificationEntity entity = NotificationEntity.fromInput(input);
        em.persist(entity);
        em.flush();
        Notification notification = entity.toNotification();
        createdEvent.fireAsync(new NotificationCreated(notification));
        return notification;
    }

    @Override
    @Transactional
    public List<Notification> storeAll(List<NotificationInput> inputs) {
        List<Notification> notifications = new ArrayList<>(inputs.size());
        for (NotificationInput input : inputs) {
            NotificationEntity entity = NotificationEntity.fromInput(input);
            em.persist(entity);
            em.flush();
            Notification notification = entity.toNotification();
            notifications.add(notification);
            createdEvent.fireAsync(new NotificationCreated(notification));
        }
        return notifications;
    }

    @Override
    public NotificationPage find(NotificationQuery query) {
        StringBuilder hql = new StringBuilder(
                "FROM NotificationEntity WHERE userId = :userId AND tenancyId = :tenancyId");
        var params = new HashMap<String, Object>();
        params.put("userId", query.userId());
        params.put("tenancyId", query.tenancyId());

        if (query.status() != null) {
            hql.append(" AND status = :status");
            params.put("status", query.status());
        }
        if (query.category() != null) {
            hql.append(" AND category = :category");
            params.put("category", query.category());
        }
        if (query.cursor() != null) {
            CursorValue cursor = decodeCursor(query.cursor());
            if (cursor != null) {
                hql.append(" AND (createdAt < :cursorCreatedAt")
                   .append(" OR (createdAt = :cursorCreatedAt AND id < :cursorId))");
                params.put("cursorCreatedAt", cursor.createdAt);
                params.put("cursorId", cursor.id);
            }
        }
        hql.append(" ORDER BY createdAt DESC, id DESC");

        int fetchLimit = query.limit() + 1;
        var jpaQuery   = em.createQuery(hql.toString(), NotificationEntity.class);
        params.forEach(jpaQuery::setParameter);
        jpaQuery.setMaxResults(fetchLimit);

        List<NotificationEntity> entities = jpaQuery.getResultList();
        boolean                  hasMore  = entities.size() > query.limit();
        List<NotificationEntity> pageEntities = hasMore
                                                ? entities.subList(0, query.limit())
                                                : entities;

        List<Notification> notifications = new ArrayList<>(pageEntities.size());
        for (NotificationEntity entity : pageEntities) {
            notifications.add(entity.toNotification());
        }

        String nextCursor = null;
        if (hasMore && !pageEntities.isEmpty()) {
            NotificationEntity last = pageEntities.getLast();
            nextCursor = encodeCursor(last.createdAt, last.id);
        }
        return new NotificationPage(notifications, nextCursor);
    }

    @Override
    public long unreadCount(String userId, String tenancyId) {
        return em.createQuery(
                         "SELECT COUNT(n) FROM NotificationEntity n " +
                         "WHERE n.userId = :userId AND n.tenancyId = :tenancyId AND n.status = :status",
                         Long.class)
                 .setParameter("userId", userId)
                 .setParameter("tenancyId", tenancyId)
                 .setParameter("status", NotificationStatus.UNREAD)
                 .getSingleResult();
    }

    @Override
    @Transactional
    public Optional<Notification> markRead(String id, String userId, String tenancyId) {
        NotificationEntity entity = em.createQuery(
                                              "FROM NotificationEntity WHERE id = :id AND userId = :userId " +
                                              "AND tenancyId = :tenancyId AND status != :dismissed",
                                              NotificationEntity.class)
                                      .setParameter("id", id)
                                      .setParameter("userId", userId)
                                      .setParameter("tenancyId", tenancyId)
                                      .setParameter("dismissed", NotificationStatus.DISMISSED)
                                      .getResultStream().findFirst().orElse(null);

        if (entity == null) {
            return Optional.empty();
        }

        NotificationStatus previousStatus = entity.status;
        entity.status = NotificationStatus.READ;
        entity.readAt = Instant.now();
        Notification notification = entity.toNotification();
        statusChangedEvent.fireAsync(new NotificationStatusChanged(notification, previousStatus));
        return Optional.of(notification);
    }

    @Override
    @Transactional
    public Optional<Notification> dismiss(String id, String userId, String tenancyId) {
        NotificationEntity entity = em.createQuery(
                                              "FROM NotificationEntity WHERE id = :id AND userId = :userId " +
                                              "AND tenancyId = :tenancyId AND status != :dismissed",
                                              NotificationEntity.class)
                                      .setParameter("id", id)
                                      .setParameter("userId", userId)
                                      .setParameter("tenancyId", tenancyId)
                                      .setParameter("dismissed", NotificationStatus.DISMISSED)
                                      .getResultStream().findFirst().orElse(null);

        if (entity == null) {
            return Optional.empty();
        }

        NotificationStatus previousStatus = entity.status;
        entity.status      = NotificationStatus.DISMISSED;
        entity.dismissedAt = Instant.now();
        Notification notification = entity.toNotification();
        statusChangedEvent.fireAsync(new NotificationStatusChanged(notification, previousStatus));
        return Optional.of(notification);
    }

    @Override
    @Transactional
    public int markAllRead(String userId, String tenancyId) {
        Instant now = Instant.now();
        int count = em.createQuery(
                              "UPDATE NotificationEntity SET status = :readStatus, readAt = :now " +
                              "WHERE userId = :userId AND tenancyId = :tenancyId AND status = :unread")
                      .setParameter("readStatus", NotificationStatus.READ)
                      .setParameter("now", now)
                      .setParameter("userId", userId)
                      .setParameter("tenancyId", tenancyId)
                      .setParameter("unread", NotificationStatus.UNREAD)
                      .executeUpdate();

        if (count > 0) {
            allReadEvent.fireAsync(new AllNotificationsRead(userId, tenancyId, count));
        }
        return count;
    }

    private static String encodeCursor(Instant createdAt, String id) {
        String raw = createdAt.toEpochMilli() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    private static CursorValue decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor));
            int    sep = raw.indexOf('|');
            if (sep == -1) {return null;}
            long   epochMillis = Long.parseLong(raw.substring(0, sep));
            String id          = raw.substring(sep + 1);
            return new CursorValue(Instant.ofEpochMilli(epochMillis), id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record CursorValue(Instant createdAt, String id) {}
}
