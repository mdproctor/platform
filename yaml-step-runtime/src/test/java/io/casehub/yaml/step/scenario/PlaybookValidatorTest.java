package io.casehub.yaml.step.scenario;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookValidatorTest {

    @Test
    void validLinearScenario_returnsEmpty() {
        var def = linearScenario();
        assertThat(PlaybookValidator.validate(def)).isEmpty();
    }

    @Test
    void referencedStateDoesNotExist_returnsError() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", StateDefinition.completionDriven("A", List.of(), "NONEXISTENT", null, null));
        states.put("B", StateDefinition.terminal("B", List.of()));

        var def = new PlaybookDefinition("test", states);
        var errors = PlaybookValidator.validate(def);
        assertThat(errors).anyMatch(e -> e.contains("NONEXISTENT") && e.contains("does not exist"));
    }

    @Test
    void noTerminalState_returnsError() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", StateDefinition.completionDriven("A", List.of(), "B", null, null));
        states.put("B", StateDefinition.completionDriven("B", List.of(), "A", null, null));

        var def = new PlaybookDefinition("test", states);
        var errors = PlaybookValidator.validate(def);
        assertThat(errors).anyMatch(e -> e.contains("terminal"));
    }

    @Test
    void initialStateIsTerminal_returnsError() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("DONE", StateDefinition.terminal("DONE", List.of()));

        var def = new PlaybookDefinition("test", states);
        var errors = PlaybookValidator.validate(def);
        assertThat(errors).anyMatch(e -> e.toLowerCase().contains("initial") && e.toLowerCase().contains("terminal"));
    }

    @Test
    void deadEndState_neitherNextNorOnNorTerminal_returnsError() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", new StateDefinition("A", List.of(), null, null, null, Map.of(), false));
        states.put("B", StateDefinition.terminal("B", List.of()));

        var def = new PlaybookDefinition("test", states);
        var errors = PlaybookValidator.validate(def);
        assertThat(errors).anyMatch(e -> e.contains("dead-end") || e.contains("no outgoing"));
    }

    @Test
    void unreachableState_returnsError() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", StateDefinition.completionDriven("A", List.of(), "B", null, null));
        states.put("B", StateDefinition.terminal("B", List.of()));
        states.put("ORPHAN", StateDefinition.terminal("ORPHAN", List.of()));

        var def = new PlaybookDefinition("test", states);
        var errors = PlaybookValidator.validate(def);
        assertThat(errors).anyMatch(e -> e.contains("ORPHAN") && e.contains("unreachable"));
    }

    @Test
    void onFailureTargetDoesNotExist_returnsError() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", StateDefinition.completionDriven("A", List.of(), "B", "NONEXISTENT", null));
        states.put("B", StateDefinition.terminal("B", List.of()));

        var def = new PlaybookDefinition("test", states);
        var errors = PlaybookValidator.validate(def);
        assertThat(errors).anyMatch(e -> e.contains("NONEXISTENT") && e.contains("does not exist"));
    }

    @Test
    void deadlineTargetDoesNotExist_returnsError() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", StateDefinition.completionDriven("A", List.of(), "B", null, "120s -> NONEXISTENT"));
        states.put("B", StateDefinition.terminal("B", List.of()));

        var def = new PlaybookDefinition("test", states);
        var errors = PlaybookValidator.validate(def);
        assertThat(errors).anyMatch(e -> e.contains("NONEXISTENT") && e.contains("does not exist"));
    }

    @Test
    void eventTargetDoesNotExist_returnsError() {
        var events = Map.of("go", EventTransition.simple("NONEXISTENT"));
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", StateDefinition.eventDriven("A", List.of(), events, null, null));
        states.put("B", StateDefinition.terminal("B", List.of()));

        var def = new PlaybookDefinition("test", states);
        var errors = PlaybookValidator.validate(def);
        assertThat(errors).anyMatch(e -> e.contains("NONEXISTENT") && e.contains("does not exist"));
    }

    @Test
    void validEventDrivenScenario_returnsEmpty() {
        var events = Map.of("approve", EventTransition.simple("APPROVED"), "reject", EventTransition.simple("REJECTED"));
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("PENDING", StateDefinition.eventDriven("PENDING", List.of(), events, null, null));
        states.put("APPROVED", StateDefinition.terminal("APPROVED", List.of()));
        states.put("REJECTED", StateDefinition.terminal("REJECTED", List.of()));

        var def = new PlaybookDefinition("test", states);
        assertThat(PlaybookValidator.validate(def)).isEmpty();
    }

    private PlaybookDefinition linearScenario() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("DETECTED", StateDefinition.completionDriven("DETECTED", List.of(), "TRIAGING", "ESCALATED", null));
        states.put("TRIAGING", StateDefinition.completionDriven("TRIAGING", List.of(), "RESOLVED", null, null));
        states.put("RESOLVED", StateDefinition.terminal("RESOLVED", List.of()));
        states.put("ESCALATED", StateDefinition.terminal("ESCALATED", List.of()));
        return new PlaybookDefinition("incident", states);
    }
}
