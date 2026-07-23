package io.casehub.platform.notification.jpa;

import io.casehub.platform.api.notification.Notification;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.notification.NotificationStatus;
import io.casehub.platform.api.util.UUIDv7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "notification",
       indexes = {
               @Index(name = "idx_notification_user_status_created",
                      columnList = "user_id, tenancy_id, status, created_at DESC"),
               @Index(name = "idx_notification_user_category_created",
                      columnList = "user_id, tenancy_id, category, created_at DESC"),
               @Index(name = "idx_notification_user_created",
                      columnList = "user_id, tenancy_id, created_at")
       })
public class NotificationEntity {

    @Id
    public String id;

    @Column(name = "user_id", nullable = false)
    public String userId;

    @Column(name = "tenancy_id", nullable = false)
    public String tenancyId;

    @Column(nullable = false, length = 500)
    public String title;

    @Column(columnDefinition = "TEXT")
    public String body;

    @Column(nullable = false)
    public String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public NotificationSeverity severity;

    @Column(name = "action_url", length = 2000)
    public String actionUrl;

    @Column(name = "source_event_id", nullable = false)
    public String sourceEventId;

    @Column(name = "source_entity_type", nullable = false)
    public String sourceEntityType;

    @Column(name = "source_entity_id", nullable = false)
    public String sourceEntityId;

    @Column(name = "source_actor_id", nullable = false)
    public String sourceActorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public NotificationStatus status;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "read_at")
    public Instant readAt;

    @Column(name = "dismissed_at")
    public Instant dismissedAt;

    static NotificationEntity fromInput(NotificationInput input) {
        NotificationEntity entity = new NotificationEntity();
        entity.id               = UUIDv7.generate();
        entity.userId           = input.userId();
        entity.tenancyId        = input.tenancyId();
        entity.title            = input.title();
        entity.body             = input.body();
        entity.category         = input.category();
        entity.severity         = input.severity();
        entity.actionUrl        = input.actionUrl();
        entity.sourceEventId    = input.source().eventId();
        entity.sourceEntityType = input.source().entityType();
        entity.sourceEntityId   = input.source().entityId();
        entity.sourceActorId    = input.source().actorId();
        entity.status           = NotificationStatus.UNREAD;
        entity.createdAt        = Instant.now();
        return entity;
    }

    Notification toNotification() {
        return new Notification(
                id,
                userId,
                tenancyId,
                title,
                body,
                category,
                severity,
                actionUrl,
                new NotificationSource(sourceEventId, sourceEntityType, sourceEntityId, sourceActorId),
                status,
                createdAt,
                readAt,
                dismissedAt
        );
    }
}
