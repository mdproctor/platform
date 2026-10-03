package io.casehub.yaml.step.scenario;

import io.casehub.yaml.core.orchestration.DefaultScenarioScope;
import io.casehub.yaml.core.orchestration.ScenarioScope;
import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.ResolvedStep;
import io.casehub.yaml.step.eval.StepRunner;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioCompilerTest {

    @Test
    void linearScenario_executesAllStatesInOrder() {
        var executionLog = new CopyOnWriteArrayList<String>();

        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", StateDefinition.completionDriven("A",
                List.of(Map.of("step-a", Map.of())), "B", null, null));
        states.put("B", StateDefinition.completionDriven("B",
                List.of(Map.of("step-b", Map.of())), "C", null, null));
        states.put("C", StateDefinition.terminal("C", List.of()));

        var def = new ScenarioDefinition("test", states);
        var scope = new DefaultScenarioScope();

        StepRunner runner = (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps) {
                executionLog.add(ps.actionName());
            }
            return Result.of(Map.of());
        };

        var compiled = ScenarioCompiler.compile(def, scope, runner);
        var result = compiled.execute();

        assertThat(result.isSuccess()).isTrue();
        assertThat(executionLog).containsExactly("step-a", "step-b");
    }

    @Test
    void stepFailure_transitionsToOnFailureState() {
        var executionLog = new CopyOnWriteArrayList<String>();

        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", StateDefinition.completionDriven("A",
                List.of(Map.of("failing-step", Map.of())), "B", "ERROR", null));
        states.put("B", StateDefinition.terminal("B", List.of()));
        states.put("ERROR", StateDefinition.terminal("ERROR",
                List.of(Map.of("error-handler", Map.of()))));

        var def = new ScenarioDefinition("test", states);
        var scope = new DefaultScenarioScope();

        StepRunner runner = (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps) {
                executionLog.add(ps.actionName());
                if ("failing-step".equals(ps.actionName())) {
                    return Result.failed("step failed");
                }
            }
            return Result.of(Map.of());
        };

        var compiled = ScenarioCompiler.compile(def, scope, runner);
        var result = compiled.execute();

        assertThat(result.isSuccess()).isTrue();
        assertThat(executionLog).containsExactly("failing-step", "error-handler");
    }

    @Test
    void terminalStateWithNoSteps_returnsSuccess() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", StateDefinition.completionDriven("A",
                List.of(Map.of("step-a", Map.of())), "DONE", null, null));
        states.put("DONE", StateDefinition.terminal("DONE", List.of()));

        var def = new ScenarioDefinition("test", states);
        var scope = new DefaultScenarioScope();

        StepRunner runner = (step, resolver) -> Result.of(Map.of());

        var compiled = ScenarioCompiler.compile(def, scope, runner);
        var result = compiled.execute();

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void stepFailure_withoutOnFailure_returnsFailure() {
        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("A", StateDefinition.completionDriven("A",
                List.of(Map.of("failing-step", Map.of())), "B", null, null));
        states.put("B", StateDefinition.terminal("B", List.of()));

        var def = new ScenarioDefinition("test", states);
        var scope = new DefaultScenarioScope();

        StepRunner runner = (step, resolver) -> Result.failed("boom");

        var compiled = ScenarioCompiler.compile(def, scope, runner);
        var result = compiled.execute();

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void stepsResultsAccessibleViaVariableResolver() {
        var captured = new CopyOnWriteArrayList<String>();

        var states = new LinkedHashMap<String, StateDefinition>();
        states.put("PRODUCE", StateDefinition.completionDriven("PRODUCE",
                List.of(Map.of("producer", Map.of())), "CONSUME", null, null));
        states.put("CONSUME", StateDefinition.completionDriven("CONSUME",
                List.of(Map.of("consumer", Map.of())), "DONE", null, null));
        states.put("DONE", StateDefinition.terminal("DONE", List.of()));

        var def = new ScenarioDefinition("test", states);
        var scope = new DefaultScenarioScope();

        StepRunner runner = (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps) {
                if ("producer".equals(ps.actionName())) {
                    return Result.of(Map.of("value", "hello"));
                }
                if ("consumer".equals(ps.actionName())) {
                    Object val = resolver.resolve("${result.producer.value}");
                    captured.add(String.valueOf(val));
                    return Result.of(Map.of());
                }
            }
            return Result.of(Map.of());
        };

        var compiled = ScenarioCompiler.compile(def, scope, runner);
        compiled.execute();

        assertThat(captured).containsExactly("hello");
    }
}
