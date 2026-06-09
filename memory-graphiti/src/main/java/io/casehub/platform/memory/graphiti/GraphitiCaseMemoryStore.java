package io.casehub.platform.memory.graphiti;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.memory.*;
import io.casehub.platform.memory.graphiti.dto.*;
import io.micrometer.core.annotation.Timed;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Alternative
@Priority(2)
@ApplicationScoped
public class GraphitiCaseMemoryStore implements GraphCaseMemoryStore {

    private static final Logger LOG = Logger.getLogger(GraphitiCaseMemoryStore.class);

    static final String SEP = "::";

    @Inject @RestClient GraphitiClient client;
    @Inject CurrentPrincipal principal;

    @Override
    public Set<MemoryCapability> capabilities() {
        // TEMPORAL_GRAPH: server POST /search does not accept valid_at as a request param;
        // temporal filtering is applied client-side using validAt/invalidAt fields returned
        // per fact. Functional but not server-side prefiltered.
        // ERASE_BY_ID absent: DELETE /episode/{uuid} only removes the EpisodicNode;
        // LLM-extracted EntityNode/EntityEdge records persist. GDPR Art.17 completeness
        // cannot be guaranteed — see platform#74 and getzep/graphiti upstream.
        return Set.of(
            MemoryCapability.CHRONOLOGICAL_ORDER,
            MemoryCapability.SINCE_FILTER,
            MemoryCapability.BATCH_STORE,
            MemoryCapability.SEMANTIC_SEARCH,
            MemoryCapability.TEMPORAL_GRAPH,
            MemoryCapability.FACT_SEARCH,
            MemoryCapability.ERASE_ENTITY
        );
    }

    // ── store ─────────────────────────────────────────────────────────────────

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "store"})
    @Override
    public String store(final MemoryInput input) {
        MemoryPermissions.assertTenant(input.tenantId(), principal);
        final String episodeUuid = UUID.randomUUID().toString();
        sendAdd(input, episodeUuid);
        return episodeUuid;
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "storeAll"})
    @Override
    public List<String> storeAll(final List<MemoryInput> inputs) {
        if (inputs.isEmpty()) return List.of();
        // Pre-flight: verify all tenant assertions before any REST call
        inputs.forEach(i -> MemoryPermissions.assertTenant(i.tenantId(), principal));
        final var ids = new ArrayList<String>(inputs.size());
        for (final MemoryInput input : inputs) {
            final String uuid = UUID.randomUUID().toString();
            sendAdd(input, uuid);
            ids.add(uuid);
        }
        return List.copyOf(ids);
    }

    private void sendAdd(final MemoryInput input, final String episodeUuid) {
        final var message = new AddMessage(
            input.text(),
            episodeUuid,
            input.entityId(),
            "user",
            null,
            Instant.now(),
            sourceDescription(input)
        );
        final var request = new AddMessagesRequest(
            compoundGroupId(input.tenantId(), input.entityId()),
            List.of(message)
        );
        try {
            client.addMessages(request);
        } catch (final WebApplicationException e) {
            throw GraphitiStoreException.from(e);
        }
    }

    // ── query ─────────────────────────────────────────────────────────────────

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "query"})
    @Override
    public List<Memory> query(final MemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal);

        final boolean relevanceWithQuestion =
            query.order() == MemoryOrder.RELEVANCE && query.question() != null;

        final List<Memory> all = new ArrayList<>();
        for (final String entityId : query.entityIds()) {
            if (relevanceWithQuestion) {
                all.addAll(searchForEntity(query, entityId));
            } else {
                all.addAll(episodesForEntity(query, entityId));
            }
        }

        var stream = all.stream();
        if (query.since() != null) {
            final Instant barrier = query.since();
            stream = stream.filter(m -> !m.createdAt().isBefore(barrier));
        }
        if (!relevanceWithQuestion) {
            // CHRONOLOGICAL: merge across entities, newest first
            stream = stream.sorted(Comparator.<Memory, Instant>comparing(Memory::createdAt).reversed());
        }
        // RELEVANCE: entity-order concatenation — order already preserved per entity
        return stream.limit(query.limit()).collect(Collectors.toList());
    }

    private List<Memory> searchForEntity(final MemoryQuery query, final String entityId) {
        final var req = new GraphitiSearchRequest(
            List.of(compoundGroupId(query.tenantId(), entityId)),
            query.question(),
            query.limit()
        );
        try {
            final GraphitiSearchResponse resp = client.search(req);
            if (resp.facts() == null) return List.of();
            return resp.facts().stream()
                .map(f -> factToMemory(f, entityId, query.domain(), query.tenantId()))
                .collect(Collectors.toList());
        } catch (final WebApplicationException e) {
            throw GraphitiStoreException.from(e);
        }
    }

    private List<Memory> episodesForEntity(final MemoryQuery query, final String entityId) {
        final int lastN = query.limit() * query.entityIds().size();
        final String groupId = compoundGroupId(query.tenantId(), entityId);
        try {
            final List<GraphitiEpisodicNode> episodes = client.getEpisodes(groupId, lastN);
            if (episodes == null) return List.of();
            return episodes.stream()
                .map(ep -> episodeToMemory(ep, query.domain(), query.tenantId()))
                .collect(Collectors.toList());
        } catch (final WebApplicationException e) {
            throw GraphitiStoreException.from(e);
        }
    }

    // ── graphQuery ────────────────────────────────────────────────────────────

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "graphQuery"})
    @Override
    public List<Memory> graphQuery(final GraphMemoryQuery query) {
        MemoryPermissions.assertTenant(query.tenantId(), principal);

        // Capability checks — always require FACT_SEARCH first
        requireCapability(MemoryCapability.FACT_SEARCH);
        if (query.validAt() != null)      requireCapability(MemoryCapability.TEMPORAL_GRAPH);
        if (query.entityTypes() != null)  requireCapability(MemoryCapability.ENTITY_TYPE_FILTER);

        final List<Memory> all = new ArrayList<>();
        for (final String entityId : query.entityIds()) {
            final var req = new GraphitiSearchRequest(
                List.of(compoundGroupId(query.tenantId(), entityId)),
                query.question(),
                query.limit()
            );
            try {
                final GraphitiSearchResponse resp = client.search(req);
                if (resp.facts() == null) continue;
                resp.facts().stream()
                    .map(f -> factToMemory(f, entityId, query.domain(), query.tenantId()))
                    .forEach(all::add);
            } catch (final WebApplicationException e) {
                throw GraphitiStoreException.from(e);
            }
        }

        var stream = all.stream();
        if (query.since() != null) {
            final Instant barrier = query.since();
            stream = stream.filter(m -> !m.createdAt().isBefore(barrier));
        }
        if (query.validAt() != null) {
            final Instant at = query.validAt();
            stream = stream.filter(m -> isValidAt(m, at));
        }
        // Entity-order concatenation — order already preserved per entity
        return stream.limit(query.limit()).collect(Collectors.toList());
    }

    /**
     * Returns true if the memory is temporally valid at the given instant.
     * Uses VALID_FROM / VALID_UNTIL attributes populated by factToMemory().
     */
    private static boolean isValidAt(final Memory m, final Instant at) {
        final String validFrom = m.attributes().get(MemoryAttributeKeys.VALID_FROM);
        final String validUntil = m.attributes().get(MemoryAttributeKeys.VALID_UNTIL);
        if (validFrom == null) return true; // no temporal data → include
        final Instant from = Instant.parse(validFrom);
        if (at.isBefore(from)) return false;
        if (validUntil != null) {
            final Instant until = Instant.parse(validUntil);
            if (!at.isBefore(until)) return false; // at >= until → fact expired
        }
        return true;
    }

    // ── erase ─────────────────────────────────────────────────────────────────

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "erase"})
    @Override
    public void erase(final EraseRequest request) {
        MemoryPermissions.assertTenant(request.tenantId(), principal);
        requireCapability(MemoryCapability.ERASE_DOMAIN_CASE); // throws
    }

    @Timed(value = "casehub.memory.graphiti", histogram = true, extraTags = {"operation", "eraseEntity"})
    @Override
    public void eraseEntity(final String entityId, final String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal);
        try {
            client.deleteGroup(compoundGroupId(tenantId, entityId));
        } catch (final WebApplicationException e) {
            throw GraphitiStoreException.from(e);
        }
    }

    /**
     * Not supported — Graphiti's DELETE /episode/{uuid} removes the source EpisodicNode only;
     * LLM-extracted EntityNode and EntityEdge records derived from that episode persist in the
     * graph. GDPR Art.17 complete erasure cannot be guaranteed. Use {@link #eraseEntity} for
     * full removal of all data for an entityId.
     *
     * @throws MemoryCapabilityException always — re-declare ERASE_BY_ID in capabilities() once
     *     cascade support is available upstream (getzep/graphiti, platform#74)
     */
    @Override
    public void eraseById(final String memoryId, final String tenantId) {
        MemoryPermissions.assertTenant(tenantId, principal);
        requireCapability(MemoryCapability.ERASE_BY_ID);
    }

    // ── mapping helpers ───────────────────────────────────────────────────────

    private static Memory factToMemory(
            final FactResult f,
            final String entityId,
            final MemoryDomain domain,
            final String tenantId) {
        final var attrs = new HashMap<String, String>();
        if (f.validAt() != null)   attrs.put(MemoryAttributeKeys.VALID_FROM,  f.validAt().toString());
        if (f.invalidAt() != null) attrs.put(MemoryAttributeKeys.VALID_UNTIL, f.invalidAt().toString());
        return new Memory(
            f.uuid(),
            entityId,
            domain,
            tenantId,
            null,
            f.fact() != null ? f.fact() : "",
            Map.copyOf(attrs),
            f.createdAt() != null ? f.createdAt() : Instant.EPOCH
        );
    }

    private static Memory episodeToMemory(
            final GraphitiEpisodicNode ep,
            final MemoryDomain domain,
            final String tenantId) {
        final String entityId = extractEntityId(ep.groupId(), tenantId);
        final var attrs = new HashMap<String, String>();
        // EpisodicNode.valid_at ≈ store timestamp (not LLM-extracted temporal).
        // Still surfaced as VALID_FROM so callers can see when the episode was recorded.
        if (ep.validAt() != null) attrs.put(MemoryAttributeKeys.VALID_FROM, ep.validAt().toString());
        return new Memory(
            ep.uuid(),
            entityId,
            domain,
            tenantId,
            null,
            ep.content() != null ? ep.content() : "",
            Map.copyOf(attrs),
            ep.createdAt() != null ? ep.createdAt() : Instant.EPOCH
        );
    }

    // ── static helpers ────────────────────────────────────────────────────────

    static String compoundGroupId(final String tenantId, final String entityId) {
        return tenantId + SEP + entityId;
    }

    static String extractEntityId(final String groupId, final String tenantId) {
        if (groupId == null) return "";
        final String prefix = tenantId + SEP;
        return groupId.startsWith(prefix) ? groupId.substring(prefix.length()) : groupId;
    }

    private static String sourceDescription(final MemoryInput input) {
        final String base = "domain=" + input.domain().name();
        return input.caseId() != null ? base + ";caseId=" + input.caseId() : base;
    }
}
