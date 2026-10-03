package io.casehub.yaml.statemachine.generator;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record StateMachineModel(
        String name,
        String pkg,
        List<String> states,
        Set<String> terminalStates,
        List<EventDef> events,
        List<TransitionDef> transitions) {

    public record EventDef(String name, Map<String, String> fields) {
        public EventDef {
            fields = fields != null ? Map.copyOf(fields) : Map.of();
        }
    }

    public record TransitionDef(
            String fromState,
            String eventName,
            String toState,
            String guard) {}
}
