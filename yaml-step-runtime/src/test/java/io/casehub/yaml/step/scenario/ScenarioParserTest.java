package io.casehub.yaml.step.scenario;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioParserTest {

    @Test
    void parseLinearScenario_extractsMetadataAndSteps() {
        var detected = List.<Object>of(
                Map.of("next", "TRIAGING"),
                Map.of("on-failure", "ESCALATED"),
                Map.of("notify.team", Map.of("channel", "ops"))
        );
        var triaging = List.<Object>of(
                Map.of("next", "RESOLVED"),
                Map.of("classify", Map.of())
        );

        var states = new LinkedHashMap<String, Object>();
        states.put("DETECTED", detected);
        states.put("TRIAGING", triaging);
        states.put("RESOLVED", "terminal");
        states.put("ESCALATED", "terminal");

        var root = Map.<String, Object>of("states", states);

        ScenarioDefinition def = ScenarioParser.parse("test", root);

        assertThat(def.initialState()).isEqualTo("DETECTED");
        assertThat(def.states().get("DETECTED").next()).isEqualTo("TRIAGING");
        assertThat(def.states().get("DETECTED").onFailure()).isEqualTo("ESCALATED");
        assertThat(def.states().get("DETECTED").steps()).hasSize(1);
        assertThat(def.states().get("RESOLVED").isTerminal()).isTrue();
        assertThat(def.states().get("TRIAGING").steps()).hasSize(1);
    }

    @Test
    void parseEventDrivenState_extractsOnEvents() {
        var pending = List.<Object>of(
                Map.of("on", Map.of("approve", "APPROVED", "reject", "REJECTED")),
                Map.of("validate.order", Map.of())
        );

        var states = new LinkedHashMap<String, Object>();
        states.put("PENDING", pending);
        states.put("APPROVED", "terminal");
        states.put("REJECTED", "terminal");

        var root = Map.<String, Object>of("states", states);
        ScenarioDefinition def = ScenarioParser.parse("test", root);

        assertThat(def.states().get("PENDING").events()).containsKey("approve");
        assertThat(def.states().get("PENDING").events().get("approve"))
                .isInstanceOf(EventTransition.Simple.class);
        assertThat(def.states().get("PENDING").events().get("approve").target())
                .isEqualTo("APPROVED");
        assertThat(def.states().get("PENDING").next()).isNull();
        assertThat(def.states().get("PENDING").steps()).hasSize(1);
    }

    @Test
    void parseGuardedEvent_extractsToAndWhen() {
        var approved = List.<Object>of(
                Map.of("on", Map.of(
                        "ship", Map.of("to", "SHIPPED", "when", "${inventory.available}"),
                        "cancel", "CANCELLED"
                ))
        );

        var states = new LinkedHashMap<String, Object>();
        states.put("APPROVED", approved);
        states.put("SHIPPED", "terminal");
        states.put("CANCELLED", "terminal");

        var root = Map.<String, Object>of("states", states);
        ScenarioDefinition def = ScenarioParser.parse("test", root);

        var shipEvent = def.states().get("APPROVED").events().get("ship");
        assertThat(shipEvent).isInstanceOf(EventTransition.Guarded.class);
        assertThat(shipEvent.target()).isEqualTo("SHIPPED");
        assertThat(((EventTransition.Guarded) shipEvent).when()).isEqualTo("${inventory.available}");

        var cancelEvent = def.states().get("APPROVED").events().get("cancel");
        assertThat(cancelEvent).isInstanceOf(EventTransition.Simple.class);
    }

    @Test
    void parseDeadlineExpression_preservesRawString() {
        var detected = List.<Object>of(
                Map.of("next", "RESOLVED"),
                Map.of("deadline", "${config.sla.timeout} -> ESCALATED"),
                Map.of("notify", Map.of())
        );

        var states = new LinkedHashMap<String, Object>();
        states.put("DETECTED", detected);
        states.put("RESOLVED", "terminal");
        states.put("ESCALATED", "terminal");

        var root = Map.<String, Object>of("states", states);
        ScenarioDefinition def = ScenarioParser.parse("test", root);

        assertThat(def.states().get("DETECTED").deadline())
                .isEqualTo("${config.sla.timeout} -> ESCALATED");
    }

    @Test
    void parseTerminalWithSteps_preservesSteps() {
        var escalated = List.<Object>of(
                Map.of("terminal", true),
                Map.of("notify.escalation", Map.of("severity", "critical"))
        );

        var states = new LinkedHashMap<String, Object>();
        states.put("A", List.<Object>of(Map.of("next", "ESCALATED"), Map.of("step1", Map.of())));
        states.put("ESCALATED", escalated);

        var root = Map.<String, Object>of("states", states);
        ScenarioDefinition def = ScenarioParser.parse("test", root);

        assertThat(def.states().get("ESCALATED").isTerminal()).isTrue();
        assertThat(def.states().get("ESCALATED").steps()).hasSize(1);
    }

    @Test
    void parseMatchBasedEvents() {
        var detected = List.<Object>of(
                Map.of("on", Map.of(
                        "assess", List.of(
                                Map.of("match", Map.of("severity", "critical"), "to", "IMMEDIATE"),
                                Map.of("match", Map.of("severity", "low"), "to", "DEFERRED"),
                                Map.of("to", "STANDARD")
                        )
                ))
        );

        var states = new LinkedHashMap<String, Object>();
        states.put("DETECTED", detected);
        states.put("IMMEDIATE", "terminal");
        states.put("DEFERRED", "terminal");
        states.put("STANDARD", "terminal");

        var root = Map.<String, Object>of("states", states);
        ScenarioDefinition def = ScenarioParser.parse("test", root);

        var assessEvent = def.states().get("DETECTED").events().get("assess");
        assertThat(assessEvent).isInstanceOf(EventTransition.MatchBased.class);
        var matchBased = (EventTransition.MatchBased) assessEvent;
        assertThat(matchBased.cases()).hasSize(3);
        assertThat(matchBased.cases().get(0).target()).isEqualTo("IMMEDIATE");
        assertThat(matchBased.cases().get(0).match()).containsEntry("severity", "critical");
        assertThat(matchBased.cases().get(2).match()).isNull();
    }

    @Test
    void parsesMultiDocumentFrontMatter() {
        String yaml = """
                scenario: test-scenario
                ---
                states:
                  idle:
                    - next: done
                  done: terminal
                """;

        ScenarioDefinition def = ScenarioParser.parseYaml(yaml);

        assertThat(def.name()).isEqualTo("test-scenario");
        assertThat(def.states()).containsKey("idle");
        assertThat(def.states()).containsKey("done");
    }

    @Test
    void parseYaml_singleDocument_backwardCompatible() {
        String yaml = """
                scenario: legacy
                states:
                  start:
                    - next: end
                  end: terminal
                """;

        ScenarioDefinition def = ScenarioParser.parseYaml(yaml);

        assertThat(def.name()).isEqualTo("legacy");
        assertThat(def.states()).containsKey("start");
    }
}
