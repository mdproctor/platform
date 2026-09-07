package io.casehub.platform.view;

import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.view.LabelPatternMatcher;
import io.casehub.platform.api.view.SubjectViewEvent;
import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.ViewEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class SubjectViewEvaluator {

    public Map<UUID, String> evaluateMembership(
            Set<String> subjectLabelPaths,
            List<SubjectViewSpec> views) {
        return views.stream()
                    .filter(v -> subjectLabelPaths.stream()
                                                  .anyMatch(p -> LabelPatternMatcher.matches(v.labelPattern(), p)))
                    .collect(Collectors.toMap(SubjectViewSpec::id, SubjectViewSpec::name));
    }

    public Map<UUID, String> evaluateMembership(
            Set<String> subjectLabelPaths,
            List<SubjectViewSpec> views,
            Path subjectScope) {
        if (subjectScope == null) {
            return evaluateMembership(subjectLabelPaths, views);
        }
        var filtered = views.stream()
                            .filter(v -> v.scope() == null
                                         || v.scope().equals(subjectScope)
                                         || v.scope().isAncestorOf(subjectScope))
                            .toList();
        return evaluateMembership(subjectLabelPaths, filtered);
    }


    public List<SubjectViewEvent> computeEvents(
            UUID subjectId,
            String tenancyId,
            Map<UUID, String> before,
            Map<UUID, String> after) {
        List<SubjectViewEvent> events = new ArrayList<>();

        before.forEach((id, name) -> {
            if (after.containsKey(id)) {
                events.add(new SubjectViewEvent(subjectId, id,
                                                after.get(id), ViewEventType.CHANGED, tenancyId));
            } else {
                events.add(new SubjectViewEvent(subjectId, id, name,
                                                ViewEventType.REMOVED, tenancyId));
            }
        });

        after.forEach((id, name) -> {
            if (!before.containsKey(id)) {
                events.add(new SubjectViewEvent(subjectId, id, name,
                                                ViewEventType.ADDED, tenancyId));
            }
        });

        return events;
    }
}
