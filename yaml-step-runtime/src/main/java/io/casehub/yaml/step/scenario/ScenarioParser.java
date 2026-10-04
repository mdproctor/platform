package io.casehub.yaml.step.scenario;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ScenarioParser {

    private static final Set<String> STATE_METADATA_KEYS = Set.of("next", "on-failure", "deadline", "on", "terminal");

    private ScenarioParser() {}

    @SuppressWarnings("unchecked")
    public static ScenarioDefinition parseYaml(String yaml) {
        var loader = new org.yaml.snakeyaml.Yaml();
        var documents = new java.util.ArrayList<Object>();
        for (Object doc : loader.loadAll(yaml)) {
            documents.add(doc);
        }

        if (documents.size() == 1) {
            var root = (Map<String, Object>) documents.get(0);
            var name = (String) root.getOrDefault("scenario", "unnamed");
            return parse(name, root);
        }

        var meta = (Map<String, Object>) documents.get(0);
        var name = (String) meta.getOrDefault("scenario", "unnamed");
        var content = documents.get(1);

        if (content instanceof Map) {
            return parse(name, (Map<String, Object>) content);
        }

        throw new IllegalArgumentException(
                "Second YAML document must be a mapping (states or metadata)");
    }

    @SuppressWarnings("unchecked")
    public static ScenarioDefinition parse(String name, Map<String, Object> root) {
        var statesRaw = (Map<String, Object>) root.get("states");
        if (statesRaw == null) {
            throw new IllegalArgumentException("Scenario must have a 'states' key");
        }

        var states = new LinkedHashMap<String, StateDefinition>();
        for (var entry : statesRaw.entrySet()) {
            states.put(entry.getKey(), parseState(entry.getKey(), entry.getValue()));
        }

        return new ScenarioDefinition(name, states);
    }

    @SuppressWarnings("unchecked")
    private static StateDefinition parseState(String stateName, Object value) {
        if ("terminal".equals(value)) {
            return StateDefinition.terminal(stateName, List.of());
        }

        if (!(value instanceof List<?> entries)) {
            throw new IllegalArgumentException("State '" + stateName + "': value must be a list or 'terminal'");
        }

        String next = null;
        String onFailure = null;
        String deadline = null;
        Map<String, EventTransition> events = new HashMap<>();
        boolean isTerminal = false;
        var steps = new java.util.ArrayList<Map<String, Object>>();

        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new IllegalArgumentException("State '" + stateName + "': each entry must be a map");
            }
            var map = (Map<String, Object>) entryMap;

            if (map.size() == 1) {
                var key = map.keySet().iterator().next();
                if (STATE_METADATA_KEYS.contains(key)) {
                    switch (key) {
                        case "next" -> next = String.valueOf(map.get("next"));
                        case "on-failure" -> onFailure = String.valueOf(map.get("on-failure"));
                        case "deadline" -> deadline = String.valueOf(map.get("deadline"));
                        case "terminal" -> isTerminal = Boolean.TRUE.equals(map.get("terminal"));
                        case "on" -> events.putAll(parseEvents((Map<String, Object>) map.get("on")));
                    }
                    continue;
                }
            }

            boolean hasMetadata = map.keySet().stream().anyMatch(STATE_METADATA_KEYS::contains);
            if (hasMetadata && map.size() > 1) {
                for (var e : map.entrySet()) {
                    if (STATE_METADATA_KEYS.contains(e.getKey())) {
                        switch (e.getKey()) {
                            case "next" -> next = String.valueOf(e.getValue());
                            case "on-failure" -> onFailure = String.valueOf(e.getValue());
                            case "deadline" -> deadline = String.valueOf(e.getValue());
                            case "terminal" -> isTerminal = Boolean.TRUE.equals(e.getValue());
                            case "on" -> events.putAll(parseEvents((Map<String, Object>) e.getValue()));
                        }
                    }
                }
                var stepMap = new LinkedHashMap<>(map);
                STATE_METADATA_KEYS.forEach(stepMap::remove);
                if (!stepMap.isEmpty()) {
                    steps.add(stepMap);
                }
                continue;
            }

            steps.add(map);
        }

        if (isTerminal) {
            return new StateDefinition(stateName, steps, null, onFailure, deadline, Map.of(), true);
        }
        if (!events.isEmpty()) {
            return StateDefinition.eventDriven(stateName, steps, events, onFailure, deadline);
        }
        return StateDefinition.completionDriven(stateName, steps, next, onFailure, deadline);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, EventTransition> parseEvents(Map<String, Object> eventsMap) {
        var result = new HashMap<String, EventTransition>();
        for (var entry : eventsMap.entrySet()) {
            result.put(entry.getKey(), parseEventTransition(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static EventTransition parseEventTransition(Object value) {
        if (value instanceof String target) {
            return EventTransition.simple(target);
        }
        if (value instanceof Map<?, ?> map) {
            var target = (String) map.get("to");
            var when = (String) map.get("when");
            if (when != null) {
                return EventTransition.guarded(target, when);
            }
            return EventTransition.simple(target);
        }
        if (value instanceof List<?> cases) {
            var matchCases = new java.util.ArrayList<EventTransition.MatchCase>();
            for (var c : cases) {
                var caseMap = (Map<String, Object>) c;
                var target = (String) caseMap.get("to");
                var match = (Map<String, Object>) caseMap.get("match");
                matchCases.add(new EventTransition.MatchCase(match, target));
            }
            return new EventTransition.MatchBased(matchCases);
        }
        throw new IllegalArgumentException("Event transition must be a string, map, or list");
    }
}
