package io.casehub.yaml.step.scenario;

import io.casehub.yaml.core.orchestration.DefaultOrcStateMachine;
import io.casehub.yaml.core.orchestration.ScenarioScope;
import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.step.eval.StepRunner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlaybookCompiler {

    private PlaybookCompiler() {}

    public static CompiledPlaybook compile(PlaybookDefinition definition, ScenarioScope scope, StepRunner runner) {
        return compile(definition, scope, runner, new VariableResolver(Map.of(), java.util.Set.of()));
    }

    public static CompiledPlaybook compile(PlaybookDefinition definition, ScenarioScope scope,
                                           StepRunner runner, VariableResolver resolver) {
        var errors = PlaybookValidator.validate(definition);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid scenario '" + definition.name() + "': " + String.join("; ", errors));
        }

        var initialState = definition.initialState().intern();
        var builder = DefaultOrcStateMachine.<String>builder(definition.name(), initialState);

        var stateSteps = new HashMap<String, List<Map<String, Object>>>();

        for (var entry : definition.states().entrySet()) {
            var stateName = entry.getKey().intern();
            var stateDef = entry.getValue();

            stateSteps.put(stateName, stateDef.steps());

            if (stateDef.isTerminal()) {
                builder.terminal(stateName);
                continue;
            }

            if (stateDef.next() != null) {
                builder.transition(stateName, stateDef.next().intern());
            }

            if (stateDef.onFailure() != null) {
                var failTarget = stateDef.onFailure().intern();
                builder.transition(stateName, failTarget);
            }

            if (stateDef.deadline() != null) {
                var deadlineTarget = PlaybookValidator.parseDeadlineTarget(stateDef.deadline());
                if (deadlineTarget != null) {
                    builder.transition(stateName, deadlineTarget.intern());
                }
            }

            for (var eventEntry : stateDef.events().entrySet()) {
                registerEventTransitions(builder, stateName, eventEntry.getKey(), eventEntry.getValue());
            }
        }

        var sm = builder.build();

        var resultResolver = resolver.withObjectScope("result",
                name -> scope.resultStore().hasCompleted(name) ? scope.resultStore().result(name) : null);

        return new CompiledPlaybook(sm, scope, definition, resultResolver, runner, Map.copyOf(stateSteps));
    }

    private static void registerEventTransitions(DefaultOrcStateMachine.Builder<String> builder,
                                                  String from, String event, EventTransition transition) {
        switch (transition) {
            case EventTransition.Simple s -> builder.transition(from, s.target().intern());
            case EventTransition.Guarded g -> builder.transition(from, g.target().intern());
            case EventTransition.MatchBased m -> {
                for (var c : m.cases()) {
                    builder.transition(from, c.target().intern());
                }
            }
        }
    }
}
