package io.casehub.platform.memory.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.cognitive.ConfidenceOrigin;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.PrincipalId;
import io.casehub.neocortex.memory.*;
import io.micrometer.core.annotation.Timed;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class JpaMemoryStore implements CaseMemoryStore {

    @Override
    public Set<MemoryCapability> capabilities() {
        return Set.of(
            MemoryCapability.CHRONOLOGICAL_ORDER,
            MemoryCapability.DOMAIN_SCOPED,
            MemoryCapability.CASE_SCOPED,
            MemoryCapability.SINCE_FILTER,
            MemoryCapability.BATCH_STORE,
            MemoryCapability.FULL_TEXT_SEARCH,
            MemoryCapability.ERASE_BY_ID,
            MemoryCapability.ERASE_ENTITY,
            MemoryCapability.ERASE_DOMAIN_CASE,
            MemoryCapability.CROSS_TENANT_ERASE
        );
    }

    @Inject CurrentPrincipal principal;
    @Inject MemoryJpaConfig config;
    @Inject EntityManager em;
    @Inject ObjectMapper objectMapper;

    private boolean requestContextActive() {
        var c = Arc.container();
        return c == null || c.requestContext().isActive();
    }

    @Timed(value = "casehub.memory.jpa", histogram = true, extraTags = {"operation", "store"})
    @Override
    @Transactional(TxType.REQUIRED)
    public String store(MemoryInput input) {
        MemoryPermissions.assertTenant(input.tenantId(), principal, requestContextActive());

        MemoryEntry entry = new MemoryEntry();
        entry.memoryId   = UUID.randomUUID().toString();
        entry.tenantId   = input.tenantId();
        entry.entityId   = input.entityId();
        entry.domain     = input.domain().name();
        entry.caseId     = input.caseId();
        entry.text       = input.text();
        entry.attributes = serializeAttributes(input.attributes());
        entry.createdAt  = Instant.now();
        entry.subjectType = input.subject() != null ? input.subject().type() : null;
        entry.confidence  = serializeConfidence(input.confidence());
        entry.pleasure    = input.pleasure();
        entry.arousal     = input.arousal();
        entry.dominance   = input.dominance();
        entry.principalId = input.principalId() != null ? input.principalId().value() : null;
        entry.sharedWith  = input.sharedWith() != null ? serializeStringSet(input.sharedWith()) : null;

        em.persist(entry);
        return entry.memoryId;
    }

    @Timed(value = "casehub.memory.jpa", histogram = true, extraTags = {"operation", "storeAll"})
    @Override
    @Transactional(TxType.REQUIRED)
    public StoreAllResult storeAll(List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return StoreAllResult.empty();
        var entries = inputs.stream().map(input -> {
            MemoryPermissions.assertTenant(input.tenantId(), principal, requestContextActive());
            MemoryEntry e = new MemoryEntry();
            e.memoryId   = UUID.randomUUID().toString();
            e.tenantId   = input.tenantId();
            e.entityId   = input.entityId();
            e.domain     = input.domain().name();
            e.caseId     = input.caseId();
            e.text       = input.text();
            e.attributes = serializeAttributes(input.attributes());
            e.createdAt  = Instant.now();
            e.subjectType = input.subject() != null ? input.subject().type() : null;
            e.confidence  = serializeConfidence(input.confidence());
            e.pleasure    = input.pleasure();
            e.arousal     = input.arousal();
            e.dominance   = input.dominance();
            e.principalId = input.principalId() != null ? input.principalId().value() : null;
            e.sharedWith  = input.sharedWith() != null ? serializeStringSet(input.sharedWith()) : null;
            return e;
        }).toList();
        entries.forEach(em::persist);
        return new StoreAllResult(entries.stream().map(e -> e.memoryId).toList(), List.of());
    }

    @Timed(value = "casehub.memory.jpa", histogram = true, extraTags = {"operation", "query"})
    @Override
    @Transactional(TxType.REQUIRED)
    public List<Memory> query(MemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal, requestContextActive());

        if (config.fts().enabled()
                && query.order() == MemoryOrder.RELEVANCE
                && query.question() != null) {
            return queryFts(query);
        }
        return queryChronological(query);
    }

    private List<Memory> queryChronological(MemoryQuery query) {
        var jpql = new StringBuilder(
            "FROM MemoryEntry WHERE tenantId = :tenantId AND entityId IN (:entityIds) AND domain = :domain");
        if (query.caseId() != null) jpql.append(" AND caseId = :caseId");
        if (query.since()  != null) jpql.append(" AND createdAt >= :since");
        jpql.append(" ORDER BY createdAt DESC");

        var jq = em.createQuery(jpql.toString(), MemoryEntry.class)
            .setParameter("tenantId",  query.tenantId())
            .setParameter("entityIds", query.entityIds())
            .setParameter("domain",    query.domain().name())
            .setMaxResults(query.limit());

        if (query.caseId() != null) jq.setParameter("caseId", query.caseId());
        if (query.since()  != null) jq.setParameter("since",  query.since());

        return jq.getResultList().stream().map(this::toMemory)
            .filter(m -> isVisible(m, query.callerPrincipalId())).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Memory> queryFts(MemoryQuery query) {
        var sql = new StringBuilder("""
            SELECT * FROM memory_entry
            WHERE tenant_id = :tenantId AND entity_id IN (:entityIds) AND domain = :domain
              AND to_tsvector(CAST(:lang AS regconfig), text)
                  @@ websearch_to_tsquery(CAST(:lang AS regconfig), :question)
            """);
        if (query.caseId() != null) sql.append("  AND case_id = :caseId\n");
        if (query.since()  != null) sql.append("  AND created_at >= :since\n");
        sql.append("""
            ORDER BY ts_rank(
                to_tsvector(CAST(:lang AS regconfig), text),
                websearch_to_tsquery(CAST(:lang AS regconfig), :question)
            ) DESC
            """);

        var nq = em.createNativeQuery(sql.toString(), MemoryEntry.class)
            .setParameter("tenantId",  query.tenantId())
            .setParameter("entityIds", query.entityIds())
            .setParameter("domain",    query.domain().name())
            .setParameter("lang",      config.fts().language())
            .setParameter("question",  query.question())
            .setMaxResults(query.limit());

        if (query.caseId() != null) nq.setParameter("caseId", query.caseId());
        if (query.since()  != null) nq.setParameter("since",  query.since());

        return ((List<MemoryEntry>) nq.getResultList()).stream().map(this::toMemory)
            .filter(m -> isVisible(m, query.callerPrincipalId())).toList();
    }

    @Timed(value = "casehub.memory.jpa", histogram = true, extraTags = {"operation", "erase"})
    @Override
    @Transactional(TxType.REQUIRED)
    public int erase(EraseRequest request) {
        MemoryPermissions.assertTenant(request.tenantId(), principal, requestContextActive());

        var jpql = new StringBuilder(
            "DELETE FROM MemoryEntry WHERE tenantId = :tenantId AND entityId = :entityId AND domain = :domain");
        if (request.caseId() != null) jpql.append(" AND caseId = :caseId");

        var q = em.createQuery(jpql.toString())
            .setParameter("tenantId", request.tenantId())
            .setParameter("entityId", request.entityId())
            .setParameter("domain",   request.domain().name());
        if (request.caseId() != null) q.setParameter("caseId", request.caseId());

        final int count = q.executeUpdate();
        em.clear();
        return count;
    }

    @Timed(value = "casehub.memory.jpa", histogram = true, extraTags = {"operation", "eraseById"})
    @Override
    @Transactional(TxType.REQUIRED)
    public void eraseById(String memoryId, String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        // entityId in WHERE: mismatch → 0 rows deleted, silent no-op.
        em.createQuery(
                "DELETE FROM MemoryEntry WHERE memoryId = :id AND entityId = :entityId AND tenantId = :tenantId")
            .setParameter("id",       memoryId)
            .setParameter("entityId", entityId)
            .setParameter("tenantId", tenantId)
            .executeUpdate();
        em.clear();
    }

    @Timed(value = "casehub.memory.jpa", histogram = true, extraTags = {"operation", "eraseEntity"})
    @Override
    @Transactional(TxType.REQUIRED)
    public int eraseEntity(String entityId, String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal, requestContextActive());
        final int count = em.createQuery(
                "DELETE FROM MemoryEntry WHERE tenantId = :tenantId AND entityId = :entityId")
            .setParameter("tenantId", tenantId)
            .setParameter("entityId", entityId)
            .executeUpdate();
        em.clear();
        return count;
    }

    @Timed(value = "casehub.memory.jpa", histogram = true, extraTags = {"operation", "eraseEntityAcrossTenants"})
    @Override
    @Transactional(TxType.REQUIRED)
    public int eraseEntityAcrossTenants(String entityId, Set<String> tenantIds) {
        MemoryPermissions.assertCrossTenantAdmin(principal);
        if (tenantIds.isEmpty()) return 0;
        int count = em.createQuery(
                "DELETE FROM MemoryEntry WHERE entityId = :entityId AND tenantId IN :tenantIds")
            .setParameter("entityId", entityId)
            .setParameter("tenantIds", List.copyOf(tenantIds))
            .executeUpdate();
        em.clear();
        return count;
    }

    private Memory toMemory(MemoryEntry e) {
        Subject subject = e.subjectType != null
            ? new Subject(e.subjectType, e.entityId)
            : new Subject("unknown", e.entityId);
        return new Memory(
            e.memoryId,
            subject,
            new MemoryDomain(e.domain),
            e.tenantId,
            e.caseId,
            e.text,
            deserializeAttributes(e.attributes),
            e.createdAt,
            deserializeConfidence(e.confidence),
            e.pleasure, e.arousal, e.dominance,
            e.principalId != null ? PrincipalId.parse(e.principalId) : null,
            e.sharedWith != null ? deserializeStringSet(e.sharedWith) : null
        );
    }

    private String serializeAttributes(Map<String, String> attrs) {
        try {
            return objectMapper.writeValueAsString(attrs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize attributes", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> deserializeAttributes(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize attributes: " + json, e);
        }
    }

    private String serializeConfidence(Confidence c) {
        if (c == null) return null;
        return c.origin().name() + ":" + c.value() + ":" + (c.decayReference() != null ? c.decayReference() : "");
    }

    private Confidence deserializeConfidence(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split(":", 3);
        ConfidenceOrigin origin = ConfidenceOrigin.valueOf(parts[0]);
        double value = Double.parseDouble(parts[1]);
        Instant decay = parts.length > 2 && !parts[2].isEmpty() ? Instant.parse(parts[2]) : null;
        return new Confidence(origin, value, decay);
    }

    private String serializeStringSet(Set<String> set) {
        try {
            return objectMapper.writeValueAsString(set);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize set", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> deserializeStringSet(String json) {
        try {
            return objectMapper.readValue(json, Set.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize set: " + json, e);
        }
    }

    private static boolean isVisible(Memory m, PrincipalId caller) {
        if (caller == null || m.principalId() == null) return true;
        if (caller.equals(m.principalId())) return true;
        var shared = m.sharedWith();
        return shared != null && shared.contains(caller.value());
    }
}
