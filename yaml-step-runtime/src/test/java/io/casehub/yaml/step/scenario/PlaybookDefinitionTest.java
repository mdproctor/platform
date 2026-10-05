package io.casehub.yaml.step.scenario;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaybookDefinitionTest {

    @Test
    void validLinearScenario_constructsSuccessfully() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("DETECTED", StateDefinition.completionDriven("DETECTED",
                List.of(Map.of("notify.team", Map.of("channel", "ops"))),
                "TRIAGING", "ESCALATED", null));
        states.put("TRIAGING", StateDefinition.completionDriven("TRIAGING",
                List.of(Map.of("classify", Map.of())),
                "RESOLVED", null, null));
        states.put("RESOLVED", StateDefinition.terminal("RESOLVED", List.of()));
        states.put("ESCALATED", StateDefinition.terminal("ESCALATED",
                List.of(Map.of("notify.escalation", Map.of("severity", "critical")))));

        var def = new PlaybookDefinition("incident", states);
        assertThat(def.initialState()).isEqualTo("DETECTED");
        assertThat(def.states()).hasSize(4);
        assertThat(def.states().get("RESOLVED").isTerminal()).isTrue();
        assertThat(def.states().get("DETECTED").isTerminal()).isFalse();
    }

    @Test
    void initialState_isFirstEntryInMap() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("FIRST", StateDefinition.completionDriven("FIRST",
                List.of(), "SECOND", null, null));
        states.put("SECOND", StateDefinition.terminal("SECOND", List.of()));

        var def = new PlaybookDefinition("test", states);
        assertThat(def.initialState()).isEqualTo("FIRST");
    }

    @Test
    void emptyStates_throws() {
        assertThatThrownBy(() -> new PlaybookDefinition("test", new LinkedHashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one state");
    }

    @Test
    void eventDrivenState_hasEvents() {
        var events = Map.of(
                "approve", EventTransition.simple("APPROVED"),
                "reject", EventTransition.simple("REJECTED"));

        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("PENDING", StateDefinition.eventDriven("PENDING",
                List.of(Map.of("validate", Map.of())),
                events, null, null));
        states.put("APPROVED", StateDefinition.terminal("APPROVED", List.of()));
        states.put("REJECTED", StateDefinition.terminal("REJECTED", List.of()));

        var def = new PlaybookDefinition("order", states);
        assertThat(def.states().get("PENDING").events()).containsKey("approve");
        assertThat(def.states().get("PENDING").events().get("approve").target()).isEqualTo("APPROVED");
    }

    @Test
    void guardedEventTransition_hasWhenExpression() {
        var guarded = EventTransition.guarded("SHIPPED", "${inventory.available}");
        assertThat(guarded.target()).isEqualTo("SHIPPED");
        assertThat(guarded).isInstanceOf(EventTransition.Guarded.class);
        assertThat(((EventTransition.Guarded) guarded).when()).isEqualTo("${inventory.available}");
    }

    @Test
    void stateDefinition_completionDriven_hasNext() {
        var state = StateDefinition.completionDriven("A",
                List.of(Map.of("step1", Map.of())),
                "B", "ERROR", "120s -> TIMEOUT");
        assertThat(state.next()).isEqualTo("B");
        assertThat(state.onFailure()).isEqualTo("ERROR");
        assertThat(state.deadline()).isEqualTo("120s -> TIMEOUT");
        assertThat(state.events()).isEmpty();
        assertThat(state.isTerminal()).isFalse();
    }
}
