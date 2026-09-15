package io.casehub.platform.acl.jpa;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class TestDataCleaner {

    @Inject EntityManager em;

    @Transactional
    public void deleteAll() {
        em.createQuery("DELETE FROM AclAuditLogEntity").executeUpdate();
        em.createQuery("DELETE FROM AclEntryEntity").executeUpdate();
        em.createQuery("DELETE FROM ResourceParentEntity").executeUpdate();
    }
}