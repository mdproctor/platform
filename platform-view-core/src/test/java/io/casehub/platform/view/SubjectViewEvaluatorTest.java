package io.casehub.platform.view;

import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.view.SubjectViewEvent;
import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.ViewEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectViewEvaluatorTest {

    private final SubjectViewEvaluator evaluator = new SubjectViewEvaluator();

    private SubjectViewSpec view(String name, String pattern) {
        return new SubjectViewSpec(UUID.randomUUID(), name, "t1", pattern,
                                   Path.root(), null, null, null, null);
    }

    @Test
    void evaluateMembership_matchesWildcard() {
        var v1 = view("iot-all", "iot/**");
        var v2 = view("legal", "legal/**");

        var result = evaluator.evaluateMembership(Set.of("iot/triage/hvac"), List.of(v1, v2));

        assertThat(result).containsEntry(v1.id(), "iot-all");
        assertThat(result).doesNotContainKey(v2.id());
    }

    @Test
    void evaluateMembership_multipleViewsMatch() {
        var v1 = view("iot-all", "iot/**");
        var v2 = view("iot-triage", "iot/triage/*");

        var result = evaluator.evaluateMembership(Set.of("iot/triage/hvac"), List.of(v1, v2));

        assertThat(result).hasSize(2);
        assertThat(result).containsEntry(v1.id(), "iot-all");
        assertThat(result).containsEntry(v2.id(), "iot-triage");
    }

    @Test
    void evaluateMembership_noMatch() {
        var result = evaluator.evaluateMembership(
                Set.of("iot/triage/hvac"), List.of(view("legal", "legal/**")));
        assertThat(result).isEmpty();
    }

    @Test
    void evaluateMembership_emptyLabels() {
        var result = evaluator.evaluateMembership(Set.of(), List.of(view("all", "iot/**")));
        assertThat(result).isEmpty();
    }

    @Test
    void evaluateMembership_emptyViews() {
        var result = evaluator.evaluateMembership(Set.of("iot/triage/hvac"), List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void computeEvents_added() {
        var               viewId    = UUID.randomUUID();
        var               subjectId = UUID.randomUUID();
        Map<UUID, String> before    = Map.of();
        Map<UUID, String> after     = Map.of(viewId, "iot-triage");

        var events = evaluator.computeEvents(subjectId, "t1", before, after);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(ViewEventType.ADDED);
        assertThat(events.get(0).viewName()).isEqualTo("iot-triage");
        assertThat(events.get(0).subjectId()).isEqualTo(subjectId);
    }

    @Test
    void computeEvents_removed() {
        var               viewId = UUID.randomUUID();
        Map<UUID, String> before = Map.of(viewId, "iot-triage");
        Map<UUID, String> after  = Map.of();

        var events = evaluator.computeEvents(UUID.randomUUID(), "t1", before, after);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(ViewEventType.REMOVED);
    }

    @Test
    void computeEvents_changed() {
        var               viewId    = UUID.randomUUID();
        var               subjectId = UUID.randomUUID();
        Map<UUID, String> before    = Map.of(viewId, "view-1");
        Map<UUID, String> after     = Map.of(viewId, "view-1");

        var events = evaluator.computeEvents(subjectId, "t1", before, after);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(ViewEventType.CHANGED);
        assertThat(events.get(0).viewId()).isEqualTo(viewId);
    }

    @Test
    void computeEvents_changedUsesAfterViewName() {
        var               viewId = UUID.randomUUID();
        Map<UUID, String> before = Map.of(viewId, "old-name");
        Map<UUID, String> after  = Map.of(viewId, "new-name");

        var events = evaluator.computeEvents(UUID.randomUUID(), "t1", before, after);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(ViewEventType.CHANGED);
        assertThat(events.get(0).viewName()).isEqualTo("new-name");
    }

    @Test
    void computeEvents_mixed() {
        var               removedId = UUID.randomUUID();
        var               addedId   = UUID.randomUUID();
        var               changedId = UUID.randomUUID();
        Map<UUID, String> before    = Map.of(removedId, "old-view", changedId, "stable");
        Map<UUID, String> after     = Map.of(addedId, "new-view", changedId, "stable");

        var events = evaluator.computeEvents(UUID.randomUUID(), "t1", before, after);

        assertThat(events).hasSize(3);
        assertThat(events).extracting(SubjectViewEvent::type)
                          .containsExactlyInAnyOrder(
                                  ViewEventType.ADDED, ViewEventType.REMOVED, ViewEventType.CHANGED);
    }

    @Test
    void computeEvents_identicalMapsAllChanged() {
        var               v1    = UUID.randomUUID();
        var               v2    = UUID.randomUUID();
        Map<UUID, String> state = Map.of(v1, "a", v2, "b");

        var events = evaluator.computeEvents(UUID.randomUUID(), "t1", state, state);

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.type() == ViewEventType.CHANGED);
    }

    @Test
    void computeEvents_bothEmpty() {
        var events = evaluator.computeEvents(
                UUID.randomUUID(), "t1", Map.of(), Map.of());
        assertThat(events).isEmpty();
    }

    @Test
    void evaluateMembership_scopeFiltersIncompatibleViews() {
        var euView = new SubjectViewSpec(UUID.randomUUID(), "eu-iot", "t1",
                                         "iot/**", Path.of("eu"), null, null, null, null);
        var usView = new SubjectViewSpec(UUID.randomUUID(), "us-iot", "t1",
                                         "iot/**", Path.of("us"), null, null, null, null);

        var result = evaluator.evaluateMembership(
                Set.of("iot/triage/hvac"), List.of(euView, usView), Path.of("eu", "germany"));

        assertThat(result).containsKey(euView.id());
        assertThat(result).doesNotContainKey(usView.id());
    }

    @Test
    void evaluateMembership_nullScopeViewMatchesAll() {
        var globalView = new SubjectViewSpec(UUID.randomUUID(), "global", "t1",
                                             "iot/**", null, null, null, null, null);

        var result = evaluator.evaluateMembership(
                Set.of("iot/triage/hvac"), List.of(globalView), Path.of("eu", "germany"));

        assertThat(result).containsKey(globalView.id());
    }

    @Test
    void evaluateMembership_exactScopeMatchIncludes() {
        var view = new SubjectViewSpec(UUID.randomUUID(), "de-iot", "t1",
                                       "iot/**", Path.of("eu", "germany"), null, null, null, null);

        var result = evaluator.evaluateMembership(
                Set.of("iot/triage/hvac"), List.of(view), Path.of("eu", "germany"));

        assertThat(result).containsKey(view.id());
    }

    @Test
    void evaluateMembership_nullSubjectScopeSkipsFiltering() {
        var scopedView = new SubjectViewSpec(UUID.randomUUID(), "eu-iot", "t1",
                                             "iot/**", Path.of("eu"), null, null, null, null);

        var result = evaluator.evaluateMembership(
                Set.of("iot/triage/hvac"), List.of(scopedView), null);

        assertThat(result).containsKey(scopedView.id());
    }
}
