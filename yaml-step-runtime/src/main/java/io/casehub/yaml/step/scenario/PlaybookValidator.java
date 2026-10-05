package io.casehub.yaml.step.scenario;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public final class PlaybookValidator {

    private PlaybookValidator() {}

    public static List<String> validate(PlaybookDefinition def) {
        var errors = new ArrayList<String>();
        var stateNames = def.states().keySet();

        var initialState = def.initialState();
        if (def.states().get(initialState).isTerminal()) {
            errors.add("Initial state '" + initialState + "' must not be terminal");
        }

        boolean hasTerminal = def.states().values().stream().anyMatch(StateDefinition::isTerminal);
        if (!hasTerminal) {
            errors.add("Scenario must have at least one terminal state");
        }

        for (var entry : def.states().entrySet()) {
            var state = entry.getValue();
            validateReferences(state, stateNames, errors);
            validateDeadEnd(state, errors);
        }

        validateReachability(def, errors);

        return errors;
    }

    private static void validateReferences(StateDefinition state, Set<String> validStates, List<String> errors) {
        if (state.next() != null && !validStates.contains(state.next())) {
            errors.add("State '" + state.name() + "': next target '" + state.next() + "' does not exist");
        }
        if (state.onFailure() != null && !validStates.contains(state.onFailure())) {
            errors.add("State '" + state.name() + "': on-failure target '" + state.onFailure() + "' does not exist");
        }
        if (state.deadline() != null) {
            String deadlineTarget = parseDeadlineTarget(state.deadline());
            if (deadlineTarget != null && !validStates.contains(deadlineTarget)) {
                errors.add("State '" + state.name() + "': deadline target '" + deadlineTarget + "' does not exist");
            }
        }
        for (var eventEntry : state.events().entrySet()) {
            for (String target : collectEventTargets(eventEntry.getValue())) {
                if (!validStates.contains(target)) {
                    errors.add("State '" + state.name() + "': event '" + eventEntry.getKey()
                               + "' target '" + target + "' does not exist");
                }
            }
        }
    }

    private static void validateDeadEnd(StateDefinition state, List<String> errors) {
        if (!state.isTerminal() && state.next() == null && state.events().isEmpty()) {
            errors.add("State '" + state.name() + "' is a dead-end: no outgoing transitions (needs 'next:', 'on:', or 'terminal')");
        }
    }

    private static void validateReachability(PlaybookDefinition def, List<String> errors) {
        Set<String> reachable = new HashSet<>();
        var queue = new LinkedList<String>();
        queue.add(def.initialState());
        reachable.add(def.initialState());

        while (!queue.isEmpty()) {
            String current = queue.poll();
            var state = def.states().get(current);
            if (state == null) continue;

            addIfNew(state.next(), reachable, queue);
            addIfNew(state.onFailure(), reachable, queue);

            if (state.deadline() != null) {
                addIfNew(parseDeadlineTarget(state.deadline()), reachable, queue);
            }

            for (var event : state.events().values()) {
                for (String target : collectEventTargets(event)) {
                    addIfNew(target, reachable, queue);
                }
            }
        }

        for (String stateName : def.states().keySet()) {
            if (!reachable.contains(stateName)) {
                errors.add("State '" + stateName + "' is unreachable from initial state '" + def.initialState() + "'");
            }
        }
    }

    private static void addIfNew(String state, Set<String> reachable, LinkedList<String> queue) {
        if (state != null && reachable.add(state)) {
            queue.add(state);
        }
    }

    static String parseDeadlineTarget(String deadline) {
        if (deadline == null) return null;
        int arrowIdx = deadline.indexOf("->");
        if (arrowIdx < 0) return null;
        return deadline.substring(arrowIdx + 2).trim();
    }

    private static List<String> collectEventTargets(EventTransition transition) {
        return switch (transition) {
            case EventTransition.Simple s -> List.of(s.target());
            case EventTransition.Guarded g -> List.of(g.target());
            case EventTransition.MatchBased m -> m.cases().stream().map(EventTransition.MatchCase::target).toList();
        };
    }
}
