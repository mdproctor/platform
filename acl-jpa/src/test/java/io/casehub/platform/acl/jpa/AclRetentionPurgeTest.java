package io.casehub.platform.acl.jpa;

import io.casehub.platform.api.acl.AclAction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AclRetentionPurgeTest {

    @Inject AclRetentionPurge purge;
    @Inject EntityManager entityManager;
    @Inject TestDataCleaner cleaner;
    @Inject TestCurrentPrincipal principal;

    @BeforeEach
    void setUp() {
        principal.setTenancyId("test-tenant");
        cleaner.deleteAll();
    }

    @Test
    @Transactional
    void purgeExpiredEntries_removesExpired_retainsActive() {
        insertEntry("actor1", "case:abc", AclAction.READ, null);
        insertEntry("actor2", "case:def", AclAction.READ,
                    Instant.now().minus(1, ChronoUnit.HOURS));
        insertEntry("actor3", "case:ghi", AclAction.READ,
                    Instant.now().plus(1, ChronoUnit.HOURS));

        purge.purgeExpiredEntries();

        long remaining = entityManager.createQuery("SELECT COUNT(e) FROM AclEntryEntity e", Long.class).getSingleResult();
        assertEquals(2, remaining);
    }

    @Test
    @Transactional
    void purgeAuditLog_removesOld_retainsRecent() {
        insertAuditLog("actor1", "case:abc", "GRANT",
                       Instant.now().minus(400, ChronoUnit.DAYS));
        insertAuditLog("actor2", "case:def", "REVOKE",
                       Instant.now().minus(10, ChronoUnit.DAYS));

        purge.purgeAuditLog();

        long remaining = entityManager.createQuery("SELECT COUNT(e) FROM AclAuditLogEntity e", Long.class).getSingleResult();
        assertEquals(1, remaining);
    }

    @Test
    @Transactional
    void purgeExpiredEntries_noExpired_deletesNothing() {
        insertEntry("actor1", "case:abc", AclAction.READ, null);

        purge.purgeExpiredEntries();

        assertEquals(1, entityManager.createQuery("SELECT COUNT(e) FROM AclEntryEntity e", Long.class).getSingleResult());
    }

    @Test
    @Transactional
    void purgeAuditLog_allRecent_deletesNothing() {
        insertAuditLog("actor1", "case:abc", "GRANT", Instant.now());

        purge.purgeAuditLog();

        assertEquals(1, entityManager.createQuery("SELECT COUNT(e) FROM AclAuditLogEntity e", Long.class).getSingleResult());
    }

    private void insertEntry(String actorId, String resourceId, AclAction action, Instant expiresAt) {
        AclEntryEntity entry = new AclEntryEntity();
        entry.actorId = actorId;
        entry.resourceId = resourceId;
        entry.action = action.name();
        entry.grantedAt = Instant.now();
        entry.expiresAt = expiresAt;
        entry.tenancyId = "test-tenant";
        entityManager.persist(entry);
    }

    private void insertAuditLog(String actorId, String resourceId, String operation, Instant performedAt) {
        AclAuditLogEntity log = new AclAuditLogEntity();
        log.actorId = actorId;
        log.resourceId = resourceId;
        log.action = "READ";
        log.operation = operation;
        log.performedBy = "system";
        log.performedAt = performedAt;
        log.tenancyId = "test-tenant";
        entityManager.persist(log);
    }
}
