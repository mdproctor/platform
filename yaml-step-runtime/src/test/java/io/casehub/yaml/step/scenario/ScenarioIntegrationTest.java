package io.casehub.yaml.step.scenario;

import io.casehub.yaml.core.orchestration.DefaultScenarioScope;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.ResolvedStep;
import io.casehub.yaml.step.eval.StepRunner;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioIntegrationTest {

    @Test
    void linearIncidentLifecycle_endToEnd() {
        var detected = List.<Object>of(
                Map.of("next", "TRIAGING"),
                Map.of("on-failure", "ESCALATED"),
                Map.of("notify.triage-team", Map.of("channel", "ops"))
        );
        var triaging = List.<Object>of(
                Map.of("next", "RESPONDING"),
                Map.of("on-failure", "ESCALATED"),
                Map.of("classify.severity", Map.of())
        );
        var responding = List.<Object>of(
                Map.of("next", "RESOLVED"),
                Map.of("execute.remediation", Map.of())
        );

        var states = new LinkedHashMap<String, Object>();
        states.put("DETECTED", detected);
        states.put("TRIAGING", triaging);
        states.put("RESPONDING", responding);
        states.put("RESOLVED", "terminal");
        states.put("ESCALATED", List.<Object>of(
                Map.of("terminal", true),
                Map.of("notify.escalation", Map.of("severity", "critical"))
        ));

        var root = Map.<String, Object>of("states", states);
        var def = ScenarioParser.parse("incident", root);
        var errors = PlaybookValidator.validate(def);
        assertThat(errors).isEmpty();

        var log = new CopyOnWriteArrayList<String>();
        var scope = new DefaultScenarioScope();
        StepRunner runner = loggingRunner(log);

        var compiled = PlaybookCompiler.compile(def, scope, runner);
        var result = compiled.execute();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).containsEntry("finalState", "RESOLVED");
        assertThat(log).containsExactly("notify.triage-team", "classify.severity", "execute.remediation");
    }

    @Test
    void linearScenario_withFailure_transitionsToEscalated() {
        var detected = List.<Object>of(
                Map.of("next", "TRIAGING"),
                Map.of("on-failure", "ESCALATED"),
                Map.of("failing-step", Map.of())
        );

        var states = new LinkedHashMap<String, Object>();
        states.put("DETECTED", detected);
        states.put("TRIAGING", List.<Object>of(Map.of("next", "RESOLVED"), Map.of("classify", Map.of())));
        states.put("RESOLVED", "terminal");
        states.put("ESCALATED", List.<Object>of(
                Map.of("terminal", true),
                Map.of("notify.escalation", Map.of())
        ));

        var root = Map.<String, Object>of("states", states);
        var def = ScenarioParser.parse("incident", root);

        var log = new CopyOnWriteArrayList<String>();
        var scope = new DefaultScenarioScope();
        StepRunner runner = (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps) {
                log.add(ps.actionName());
                if ("failing-step".equals(ps.actionName())) {
                    return Result.failed("step failed");
                }
            }
            return Result.of(Map.of());
        };

        var compiled = PlaybookCompiler.compile(def, scope, runner);
        var result = compiled.execute();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).containsEntry("finalState", "ESCALATED");
        assertThat(log).containsExactly("failing-step", "notify.escalation");
    }

    @Test
    void eventDrivenScenario_returnsWaitingState() {
        var pending = List.<Object>of(
                Map.of("on", Map.of("approve", "APPROVED", "reject", "REJECTED")),
                Map.of("validate.order", Map.of())
        );

        var states = new LinkedHashMap<String, Object>();
        states.put("PENDING", pending);
        states.put("APPROVED", "terminal");
        states.put("REJECTED", "terminal");

        var root = Map.<String, Object>of("states", states);
        var def = ScenarioParser.parse("order", root);

        var log = new CopyOnWriteArrayList<String>();
        var scope = new DefaultScenarioScope();
        StepRunner runner = loggingRunner(log);

        var compiled = PlaybookCompiler.compile(def, scope, runner);
        var result = compiled.execute();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).containsEntry("waitingForEvent", true);
        assertThat(result.output()).containsEntry("currentState", "PENDING");
        assertThat(log).containsExactly("validate.order");
    }

    @Test
    void validationErrors_throwOnCompile() {
        var states = new LinkedHashMap<String, Object>();
        states.put("A", List.<Object>of(Map.of("next", "NONEXISTENT"), Map.of("step", Map.of())));
        states.put("B", "terminal");

        var root = Map.<String, Object>of("states", states);
        var def = ScenarioParser.parse("bad", root);

        var scope = new DefaultScenarioScope();
        StepRunner runner = loggingRunner(new CopyOnWriteArrayList<>());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PlaybookCompiler.compile(def, scope, runner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONEXISTENT");
    }

    @Test
    void crossStateResultAccess_producerConsumer() {
        var produce = List.<Object>of(
                Map.of("next", "CONSUME"),
                Map.of("producer", Map.of())
        );
        var consume = List.<Object>of(
                Map.of("next", "DONE"),
                Map.of("consumer", Map.of())
        );

        var states = new LinkedHashMap<String, Object>();
        states.put("PRODUCE", produce);
        states.put("CONSUME", consume);
        states.put("DONE", "terminal");

        var root = Map.<String, Object>of("states", states);
        var def = ScenarioParser.parse("pipeline", root);

        var captured = new CopyOnWriteArrayList<String>();
        var scope = new DefaultScenarioScope();
        StepRunner runner = (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps) {
                if ("producer".equals(ps.actionName())) {
                    return Result.of(Map.of("value", "hello-world"));
                }
                if ("consumer".equals(ps.actionName())) {
                    Object val = resolver.resolve("${result.producer.value}");
                    captured.add(String.valueOf(val));
                    return Result.of(Map.of());
                }
            }
            return Result.of(Map.of());
        };

        var compiled = PlaybookCompiler.compile(def, scope, runner);
        compiled.execute();

        assertThat(captured).containsExactly("hello-world");
    }

    @Test
    void terminalStateWithSteps_executesStepsThenStops() {
        var states = new LinkedHashMap<String, Object>();
        states.put("START", List.<Object>of(Map.of("next", "END"), Map.of("step-start", Map.of())));
        states.put("END", List.<Object>of(Map.of("terminal", true), Map.of("cleanup", Map.of())));

        var root = Map.<String, Object>of("states", states);
        var def = ScenarioParser.parse("cleanup-test", root);

        var log = new CopyOnWriteArrayList<String>();
        var scope = new DefaultScenarioScope();
        StepRunner runner = loggingRunner(log);

        var compiled = PlaybookCompiler.compile(def, scope, runner);
        var result = compiled.execute();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).containsEntry("finalState", "END");
        assertThat(log).containsExactly("step-start", "cleanup");
    }

    @Test
    void stepFailure_withoutOnFailure_returnsFailure() {
        var states = new LinkedHashMap<String, Object>();
        states.put("A", List.<Object>of(Map.of("next", "B"), Map.of("failing", Map.of())));
        states.put("B", "terminal");

        var root = Map.<String, Object>of("states", states);
        var def = ScenarioParser.parse("unhandled", root);

        var scope = new DefaultScenarioScope();
        StepRunner runner = (step, resolver) -> Result.failed("boom");

        var compiled = PlaybookCompiler.compile(def, scope, runner);
        var result = compiled.execute();

        assertThat(result.isSuccess()).isFalse();
    }

    private StepRunner loggingRunner(CopyOnWriteArrayList<String> log) {
        return (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps) {
                log.add(ps.actionName());
            }
            return Result.of(Map.of());
        };
    }
}
