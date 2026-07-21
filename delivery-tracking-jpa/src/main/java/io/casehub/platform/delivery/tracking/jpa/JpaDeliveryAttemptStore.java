package io.casehub.platform.delivery.tracking.jpa;

import io.casehub.platform.api.delivery.DeliveryAttempt;
import io.casehub.platform.api.delivery.DeliveryAttemptPage;
import io.casehub.platform.api.delivery.DeliveryAttemptQuery;
import io.casehub.platform.api.delivery.DeliveryAttemptStore;
import io.casehub.platform.api.delivery.DeliverySourceType;
import io.casehub.platform.api.delivery.DeliveryStatus;
import io.casehub.platform.api.delivery.EngagementEvent;
import io.casehub.platform.api.delivery.EngagementType;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@ApplicationScoped
public class JpaDeliveryAttemptStore implements DeliveryAttemptStore {

    private static final Logger LOG = Logger.getLogger(JpaDeliveryAttemptStore.class);

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "casehub.delivery.retry.claim-timeout", defaultValue = "5m")
    Duration claimTimeout;

    @ConfigProperty(name = "casehub.delivery.retention.attempt-days", defaultValue = "30")
    int defaultAttemptDays;

    @ConfigProperty(name = "casehub.delivery.retention.failed-attempt-days", defaultValue = "365")
    int defaultFailedAttemptDays;

    @ConfigProperty(name = "casehub.delivery.retention.engagement-days", defaultValue = "90")
    int defaultEngagementDays;

    @Inject
    org.eclipse.microprofile.config.Config config;

    @Override
    @Transactional
    public void store(DeliveryAttempt attempt) {
        entityManager.persist(DeliveryAttemptEntity.fromDomain(attempt));
    }

    @Override
    @Transactional
    public void update(DeliveryAttempt attempt) {
        var entity = entityManager.find(DeliveryAttemptEntity.class, attempt.id());
        if (entity == null) {
            LOG.warnf("DeliveryAttempt %s not found for update", attempt.id());
            return;
        }
        entity.sourceId        = attempt.sourceId();
        entity.sourceType      = attempt.sourceType();
        entity.channelId       = attempt.channelId();
        entity.userId          = attempt.userId();
        entity.tenancyId       = attempt.tenancyId();
        entity.deliveryType    = attempt.deliveryType();
        entity.status          = attempt.status();
        entity.attemptCount    = attempt.attemptCount();
        entity.lastAttemptedAt = attempt.lastAttemptedAt();
        entity.deliveredAt     = attempt.deliveredAt();
        entity.nextRetryAt     = attempt.nextRetryAt();
        entity.failureReason   = attempt.failureReason();
        entity.payload         = attempt.payload();
        entity.firstOpenedAt   = attempt.firstOpenedAt();
        entity.firstClickedAt  = attempt.firstClickedAt();
    }

    @Override
    public DeliveryAttempt findById(String id) {
        var entity = entityManager.find(DeliveryAttemptEntity.class, id);
        return entity != null ? entity.toDomain() : null;
    }


    @Override
    @Transactional
    public List<DeliveryAttempt> claimRetryable(Instant now, int batchSize) {
        List<DeliveryAttemptEntity> entities = entityManager.createQuery(
                                                                    "SELECT e FROM DeliveryAttemptEntity e " +
                                                                    "WHERE e.status = :status AND e.nextRetryAt IS NOT NULL AND e.nextRetryAt <= :now " +
                                                                    "ORDER BY e.nextRetryAt ASC", DeliveryAttemptEntity.class)
                                                            .setParameter("status", DeliveryStatus.RETRYING)
                                                            .setParameter("now", now)
                                                            .setMaxResults(batchSize)
                                                            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                                                            .setHint("jakarta.persistence.lock.timeout", -2)
                                                            .getResultList();

        Instant claimExpiry = now.plus(claimTimeout);
        for (DeliveryAttemptEntity entity : entities) {
            entity.nextRetryAt = claimExpiry;
        }
        entityManager.flush();

        return entities.stream().map(DeliveryAttemptEntity::toDomain).toList();
    }

    @Override
    public DeliveryAttemptPage find(DeliveryAttemptQuery query) {
        var sb = new StringBuilder("SELECT e FROM DeliveryAttemptEntity e WHERE e.tenancyId = :tenancyId");
        if (query.userId() != null) {sb.append(" AND e.userId = :userId");}
        if (query.channelId() != null) {sb.append(" AND e.channelId = :channelId");}
        if (query.status() != null) {sb.append(" AND e.status = :status");}
        if (query.sourceType() != null) {sb.append(" AND e.sourceType = :sourceType");}

        if (query.cursor() != null) {
            String[] parts = query.cursor().split("\\|", 2);
            sb.append(" AND (e.createdAt < :cursorTime OR (e.createdAt = :cursorTime AND e.id < :cursorId))");
        }
        sb.append(" ORDER BY e.createdAt DESC, e.id DESC");

        var jpql = entityManager.createQuery(sb.toString(), DeliveryAttemptEntity.class);
        jpql.setParameter("tenancyId", query.tenancyId());
        if (query.userId() != null) {jpql.setParameter("userId", query.userId());}
        if (query.channelId() != null) {jpql.setParameter("channelId", query.channelId());}
        if (query.status() != null) {jpql.setParameter("status", query.status());}
        if (query.sourceType() != null) {jpql.setParameter("sourceType", query.sourceType());}

        if (query.cursor() != null) {
            String[] parts = query.cursor().split("\\|", 2);
            jpql.setParameter("cursorTime", Instant.parse(parts[0]));
            jpql.setParameter("cursorId", parts[1]);
        }

        jpql.setMaxResults(query.limit() + 1);
        List<DeliveryAttemptEntity> results = jpql.getResultList();

        boolean                     hasMore = results.size() > query.limit();
        List<DeliveryAttemptEntity> page    = hasMore ? results.subList(0, query.limit()) : results;

        String nextCursor = null;
        if (hasMore) {
            var last = page.getLast();
            nextCursor = last.createdAt.truncatedTo(ChronoUnit.MICROS).toString() + "|" + last.id;
        }

        return new DeliveryAttemptPage(
                page.stream().map(DeliveryAttemptEntity::toDomain).toList(),
                nextCursor);
    }

    @Override
    public List<DeliveryAttempt> findBySource(String sourceId, DeliverySourceType sourceType) {
        return entityManager.createQuery(
                                    "SELECT e FROM DeliveryAttemptEntity e " +
                                    "WHERE e.sourceId = :sourceId AND e.sourceType = :sourceType " +
                                    "ORDER BY e.createdAt ASC", DeliveryAttemptEntity.class)
                            .setParameter("sourceId", sourceId)
                            .setParameter("sourceType", sourceType)
                            .getResultList()
                            .stream().map(DeliveryAttemptEntity::toDomain).toList();
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    void attemptRetentionPurge() {
        for (DeliverySourceType sourceType : DeliverySourceType.values()) {
            int attemptDays = resolveRetentionConfig(sourceType, "attempt-days", defaultAttemptDays);
            int failedDays  = resolveRetentionConfig(sourceType, "failed-attempt-days", defaultFailedAttemptDays);

            Instant attemptCutoff = Instant.now().minus(Duration.ofDays(attemptDays));
            Instant failedCutoff  = Instant.now().minus(Duration.ofDays(failedDays));

            int purged = 0;
            purged += purgeAttempts(sourceType, DeliveryStatus.DELIVERED, attemptCutoff);
            purged += purgeAttempts(sourceType, DeliveryStatus.EXPIRED, attemptCutoff);
            purged += purgeAttempts(sourceType, DeliveryStatus.FAILED, failedCutoff);
            purged += purgeStaleRetrying(sourceType, attemptCutoff);

            if (purged > 0) {
                LOG.infof("Attempt retention purge [%s]: %d records removed", sourceType, purged);
            }
        }
        purgeOrphanedPrePersist();
    }

    @Scheduled(cron = "0 30 3 * * ?")
    @Transactional
    void engagementRetentionPurge() {
        for (DeliverySourceType sourceType : DeliverySourceType.values()) {
            int     engagementDays = resolveRetentionConfig(sourceType, "engagement-days", defaultEngagementDays);
            Instant cutoff         = Instant.now().minus(Duration.ofDays(engagementDays));

            int purged = entityManager.createQuery(
                                              "DELETE FROM EngagementEventEntity e " +
                                              "WHERE e.sourceType = :sourceType AND e.recordedAt < :cutoff")
                                      .setParameter("sourceType", sourceType)
                                      .setParameter("cutoff", cutoff)
                                      .executeUpdate();

            if (purged > 0) {
                LOG.infof("Engagement retention purge [%s]: %d records removed", sourceType, purged);
            }
        }
    }

    private int purgeAttempts(DeliverySourceType sourceType, DeliveryStatus status, Instant cutoff) {
        return entityManager.createQuery(
                                    "DELETE FROM DeliveryAttemptEntity e " +
                                    "WHERE e.sourceType = :sourceType AND e.status = :status AND e.createdAt < :cutoff")
                            .setParameter("sourceType", sourceType)
                            .setParameter("status", status)
                            .setParameter("cutoff", cutoff)
                            .executeUpdate();
    }

    private int purgeStaleRetrying(DeliverySourceType sourceType, Instant cutoff) {
        return entityManager.createQuery(
                                    "DELETE FROM DeliveryAttemptEntity e " +
                                    "WHERE e.sourceType = :sourceType AND e.status = :status " +
                                    "AND e.nextRetryAt IS NOT NULL AND e.nextRetryAt < :cutoff")
                            .setParameter("sourceType", sourceType)
                            .setParameter("status", DeliveryStatus.RETRYING)
                            .setParameter("cutoff", cutoff)
                            .executeUpdate();
    }

    private void purgeOrphanedPrePersist() {
        int purged = entityManager.createQuery(
                                          "DELETE FROM DeliveryAttemptEntity e " +
                                          "WHERE e.status = :status AND e.nextRetryAt IS NULL AND e.createdAt < :cutoff")
                                  .setParameter("status", DeliveryStatus.RETRYING)
                                  .setParameter("cutoff", Instant.now().minus(claimTimeout))
                                  .executeUpdate();
        if (purged > 0) {
            LOG.infof("Orphaned pre-persist purge: %d records removed", purged);
        }
    }

    private int resolveRetentionConfig(DeliverySourceType sourceType, String suffix, int defaultValue) {
        String key = "casehub.delivery.retention.\"" + sourceType.name().toLowerCase() + "\"." + suffix;
        return config.getOptionalValue(key, Integer.class).orElse(defaultValue);
    }


    @Override
    @Transactional
    public void recordEngagement(EngagementEvent event) {
        entityManager.persist(EngagementEventEntity.fromDomain(event));
        entityManager.flush();
        if (event.type() == EngagementType.OPENED) {
            entityManager.createQuery(
                                 "UPDATE DeliveryAttemptEntity e SET e.firstOpenedAt = :ts " +
                                 "WHERE e.id = :id AND e.firstOpenedAt IS NULL")
                         .setParameter("ts", event.recordedAt())
                         .setParameter("id", event.attemptId())
                         .executeUpdate();
        }
        if (event.type() == EngagementType.CLICKED) {
            entityManager.createQuery(
                                 "UPDATE DeliveryAttemptEntity e SET e.firstClickedAt = :ts " +
                                 "WHERE e.id = :id AND e.firstClickedAt IS NULL")
                         .setParameter("ts", event.recordedAt())
                         .setParameter("id", event.attemptId())
                         .executeUpdate();
        }
        var cached = entityManager.find(DeliveryAttemptEntity.class, event.attemptId());
        if (cached != null) {
            entityManager.refresh(cached);
        }}

    @Override
    public List<EngagementEvent> findEngagementsByAttemptId(String attemptId) {
        return entityManager
                       .createQuery("FROM EngagementEventEntity e WHERE e.attemptId = :attemptId ORDER BY e.recordedAt",
                                    EngagementEventEntity.class)
                       .setParameter("attemptId", attemptId)
                       .getResultList()
                       .stream()
                       .map(EngagementEventEntity::toDomain)
                       .toList();
    }

    @Override
    public List<EngagementEvent> findEngagementsBySource(String sourceId, DeliverySourceType sourceType) {
        return entityManager
                       .createQuery("FROM EngagementEventEntity e " +
                                    "WHERE e.sourceId = :sourceId AND e.sourceType = :sourceType " +
                                    "ORDER BY e.recordedAt",
                                    EngagementEventEntity.class)
                       .setParameter("sourceId", sourceId)
                       .setParameter("sourceType", sourceType)
                       .getResultList()
                       .stream()
                       .map(EngagementEventEntity::toDomain)
                       .toList();
    }
}
