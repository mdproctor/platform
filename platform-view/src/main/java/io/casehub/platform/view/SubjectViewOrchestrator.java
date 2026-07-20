package io.casehub.platform.view;

import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.view.SubjectViewEvent;
import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.SubjectViewStore;
import io.casehub.platform.api.view.ViewEventType;
import io.casehub.platform.api.view.ViewMembershipTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@ApplicationScoped
public class SubjectViewOrchestrator {

    @Inject
    SubjectViewEvaluator evaluator;

    @Inject
    SubjectViewStore viewStore;

    @Inject
    ViewMembershipTracker tracker;

    @ConfigProperty(name = "casehub.view.cache.ttl-seconds", defaultValue = "0")
    int cacheTtlSeconds;

    private final ConcurrentHashMap<String, CachedViews> viewCache = new ConcurrentHashMap<>();

    public List<SubjectViewEvent> evaluateAndTrack(
            UUID subjectId, String tenancyId, Set<String> labelPaths) {
        var before = tracker.getLastKnownMembership(subjectId);
        var views = getViews(tenancyId);
        var after = evaluator.evaluateMembership(labelPaths, views);
        var events = evaluator.computeEvents(subjectId, tenancyId, before, after);
        tracker.updateMembership(subjectId, after);
        return events;
    }

    public List<SubjectViewEvent> evaluateAndTrack(
            UUID subjectId, String tenancyId,
            Set<String> labelPaths, Path subjectScope) {
        var before = tracker.getLastKnownMembership(subjectId);
        var views = getViews(tenancyId);
        var after = evaluator.evaluateMembership(labelPaths, views, subjectScope);
        var events = evaluator.computeEvents(subjectId, tenancyId, before, after);
        tracker.updateMembership(subjectId, after);
        return events;
    }

    public Map<UUID, List<SubjectViewEvent>> evaluateAndTrackBatch(
            Map<UUID, Set<String>> subjectLabelPaths, String tenancyId) {
        var subjectIds = subjectLabelPaths.keySet();
        var allBefore = tracker.getLastKnownMembership(subjectIds);
        var views = getViews(tenancyId);

        Map<UUID, List<SubjectViewEvent>> result = new LinkedHashMap<>();
        subjectLabelPaths.forEach((subjectId, labelPaths) -> {
            var before = allBefore.getOrDefault(subjectId, Map.of());
            var after = evaluator.evaluateMembership(labelPaths, views);
            var events = evaluator.computeEvents(subjectId, tenancyId, before, after);
            tracker.updateMembership(subjectId, after);
            result.put(subjectId, events);
        });
        return result;
    }

    public Map<UUID, List<SubjectViewEvent>> evaluateAndTrackBatch(
            Map<UUID, Set<String>> subjectLabelPaths, String tenancyId,
            Function<UUID, Path> scopeResolver) {
        var subjectIds = subjectLabelPaths.keySet();
        var allBefore = tracker.getLastKnownMembership(subjectIds);
        var views = getViews(tenancyId);

        Map<UUID, List<SubjectViewEvent>> result = new LinkedHashMap<>();
        subjectLabelPaths.forEach((subjectId, labelPaths) -> {
            var before = allBefore.getOrDefault(subjectId, Map.of());
            Path scope = scopeResolver != null ? scopeResolver.apply(subjectId) : null;
            var after = evaluator.evaluateMembership(labelPaths, views, scope);
            var events = evaluator.computeEvents(subjectId, tenancyId, before, after);
            tracker.updateMembership(subjectId, after);
            result.put(subjectId, events);
        });
        return result;
    }

    public SubjectViewSpec saveView(SubjectViewSpec spec) {
        var saved = viewStore.save(spec);
        invalidateViewCache(spec.tenancyId());
        return saved;
    }

    public List<SubjectViewEvent> deleteView(UUID viewId) {
        var spec = viewStore.findById(viewId);
        if (spec.isEmpty()) {return List.of();}
        var s = spec.get();

        Set<UUID> members = tracker.getSubjectsByView(viewId);
        List<SubjectViewEvent> events = members.stream()
                                               .map(subjectId -> new SubjectViewEvent(
                                                       subjectId, viewId, s.name(), ViewEventType.REMOVED, s.tenancyId()))
                                               .toList();

        viewStore.delete(viewId);
        invalidateViewCache(s.tenancyId());
        tracker.removeMembershipByView(viewId);
        return events;
    }

    private List<SubjectViewSpec> getViews(String tenancyId) {
        if (cacheTtlSeconds <= 0) {
            return viewStore.findByTenancy(tenancyId);
        }
        var cached = viewCache.get(tenancyId);
        if (cached != null && !cached.isExpired(cacheTtlSeconds)) {
            return cached.views();
        }
        var views = viewStore.findByTenancy(tenancyId);
        viewCache.put(tenancyId, new CachedViews(views, Instant.now()));
        return views;
    }

    private void invalidateViewCache(String tenancyId) {
        viewCache.remove(tenancyId);
    }

    private record CachedViews(List<SubjectViewSpec> views, Instant fetchedAt) {
        boolean isExpired(int ttlSeconds) {
            return Instant.now().isAfter(fetchedAt.plusSeconds(ttlSeconds));
        }
    }
}
