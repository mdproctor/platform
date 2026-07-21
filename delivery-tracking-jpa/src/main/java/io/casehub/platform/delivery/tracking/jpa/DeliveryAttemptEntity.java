package io.casehub.platform.delivery.tracking.jpa;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.DeliveryType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "delivery_attempt")
public class DeliveryAttemptEntity {

    @Id
    @Column(name = "id", length = 36)
    public String id;

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
    @Column(name = "delivery_type", nullable = false, length = 20)
    public DeliveryType deliveryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "last_attempted_at")
    public Instant lastAttemptedAt;

    @Column(name = "delivered_at")
    public Instant deliveredAt;

    @Column(name = "next_retry_at")
    public Instant nextRetryAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    public String failureReason;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    public String payload;

    @Column(name = "first_opened_at")
    public Instant firstOpenedAt;

    @Column(name = "first_clicked_at")
    public Instant firstClickedAt;

    public static DeliveryAttemptEntity fromDomain(DeliveryAttempt attempt) {
        var entity = new DeliveryAttemptEntity();
        entity.id              = attempt.id();
        entity.sourceId        = attempt.sourceId();
        entity.sourceType      = attempt.sourceType();
        entity.channelId       = attempt.channelId();
        entity.userId          = attempt.userId();
        entity.tenancyId       = attempt.tenancyId();
        entity.deliveryType    = attempt.deliveryType();
        entity.status          = attempt.status();
        entity.attemptCount    = attempt.attemptCount();
        entity.createdAt       = attempt.createdAt();
        entity.lastAttemptedAt = attempt.lastAttemptedAt();
        entity.deliveredAt     = attempt.deliveredAt();
        entity.nextRetryAt     = attempt.nextRetryAt();
        entity.failureReason   = attempt.failureReason();
        entity.payload         = attempt.payload();
        entity.firstOpenedAt   = attempt.firstOpenedAt();
        entity.firstClickedAt  = attempt.firstClickedAt();
        return entity;
    }

    public DeliveryAttempt toDomain() {
        return new DeliveryAttempt(
                id, sourceId, sourceType, channelId, userId, tenancyId,
                deliveryType, status, attemptCount,
                createdAt, lastAttemptedAt, deliveredAt,
                nextRetryAt, failureReason, payload,
                firstOpenedAt, firstClickedAt);
    }
}
