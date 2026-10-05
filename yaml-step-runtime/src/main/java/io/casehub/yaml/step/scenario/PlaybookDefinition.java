package io.casehub.yaml.step.scenario;

import java.util.LinkedHashMap;

public record PlaybookDefinition(String name, LinkedHashMap<String, StateDefinition> states) {

    public PlaybookDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Scenario name must not be blank");
        }
        if (states == null || states.isEmpty()) {
            throw new IllegalArgumentException("Scenario must have at least one state");
        }
        states = new LinkedHashMap<>(states);
    }

    public String initialState() {
        return states.keySet().iterator().next();
    }
}
