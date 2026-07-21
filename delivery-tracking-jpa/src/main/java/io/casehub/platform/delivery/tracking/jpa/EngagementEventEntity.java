package io.casehub.platform.delivery.tracking.jpa;

import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.EngagementEvent;
import io.casehub.platform.api.delivery.EngagementType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "engagement_event")
public class EngagementEventEntity {

    @Id
    @Column(name = "id", length = 36)
    public String id;

    @Column(name = "attempt_id", length = 36)
    public String attemptId;

    @Column(name = "source_id")
    public String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    public DeliverySourceType sourceType;

    @Column(name = "channel_id", nullable = false)
    public String channelId;

    @Column(name = "user_id", nullable = false)
    public String userId;

    @Column(name = "tenancy_id", nullable = false)
    public String tenancyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    public EngagementType type;

    @Column(name = "recorded_at", nullable = false)
    public Instant recordedAt;

    @Column(name = "metadata", columnDefinition = "TEXT")
    public String metadata;

    public static EngagementEventEntity fromDomain(EngagementEvent event) {
        var entity = new EngagementEventEntity();
        entity.id         = event.id();
        entity.attemptId  = event.attemptId();
        entity.sourceId   = event.sourceId();
        entity.sourceType = event.sourceType();
        entity.channelId  = event.channelId();
        entity.userId     = event.userId();
        entity.tenancyId  = event.tenancyId();
        entity.type       = event.type();
        entity.recordedAt = event.recordedAt();
        entity.metadata   = event.metadata();
        return entity;
    }

    public EngagementEvent toDomain() {
        return new EngagementEvent(
                id, attemptId, sourceId, sourceType, channelId,
                userId, tenancyId, type, recordedAt, metadata);
    }
}
