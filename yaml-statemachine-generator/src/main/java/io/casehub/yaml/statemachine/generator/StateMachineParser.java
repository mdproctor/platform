package io.casehub.yaml.statemachine.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.jackson.YamlMappers;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StateMachineParser {

    private static final ObjectMapper MAPPER = YamlMappers.create();

    private StateMachineParser() {}

    @SuppressWarnings("unchecked")
    public static StateMachineModel parse(File yamlFile, String name, String pkg) {
        Map<String, Object> root;
        try {
            root = MAPPER.readValue(yamlFile, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var eventDefs = parseEventDefs((Map<String, Object>) root.get("events"));
        var statesRaw = (Map<String, Object>) root.get("states");
        if (statesRaw == null) {
            throw new IllegalArgumentException("YAML must have a 'states' key");
        }

        var states = new ArrayList<>(statesRaw.keySet());
        var terminalStates = new LinkedHashSet<String>();
        var transitions = new ArrayList<StateMachineModel.TransitionDef>();
        var eventNames = new LinkedHashSet<String>();

        for (var entry : statesRaw.entrySet()) {
            var stateName = entry.getKey();
            var value = entry.getValue();

            if ("terminal".equals(value)) {
                terminalStates.add(stateName);
                continue;
            }

            if (value instanceof Map<?, ?> map) {
                var terminal = map.get("terminal");
                if (Boolean.TRUE.equals(terminal)) {
                    terminalStates.add(stateName);
                }
                var on = (Map<String, Object>) map.get("on");
                if (on != null) {
                    extractTransitions(stateName, on, transitions, eventNames);
                }
                continue;
            }

            if (!(value instanceof List<?> entries)) continue;

            for (Object item : entries) {
                if (!(item instanceof Map<?, ?> itemMap)) continue;
                var map = (Map<String, Object>) itemMap;

                for (var e : map.entrySet()) {
                    switch (e.getKey()) {
                        case "terminal" -> {
                            if (Boolean.TRUE.equals(e.getValue()))
                                terminalStates.add(stateName);
                        }
                        case "on" -> extractTransitions(stateName,
                            (Map<String, Object>) e.getValue(),
                            transitions, eventNames);
                        case "next" -> transitions.add(
                            new StateMachineModel.TransitionDef(
                                stateName, null,
                                String.valueOf(e.getValue()), null));
                        default -> {} // ignore steps and other metadata
                    }
                }
            }
        }

        var events = new ArrayList<StateMachineModel.EventDef>();
        var definedEvents = new LinkedHashMap<String, Map<String, String>>();
        for (var ed : eventDefs) {
            definedEvents.put(ed.name(), ed.fields());
        }
        for (String en : eventNames) {
            var fields = definedEvents.getOrDefault(en, Map.of());
            events.add(new StateMachineModel.EventDef(en, fields));
        }
        for (var ed : eventDefs) {
            if (!eventNames.contains(ed.name())) {
                events.add(ed);
            }
        }

        return new StateMachineModel(name, pkg,
            List.copyOf(states), Set.copyOf(terminalStates),
            List.copyOf(events), List.copyOf(transitions));
    }

    @SuppressWarnings("unchecked")
    private static void extractTransitions(String fromState,
            Map<String, Object> on,
            List<StateMachineModel.TransitionDef> transitions,
            Set<String> eventNames) {
        for (var e : on.entrySet()) {
            var eventName = e.getKey();
            eventNames.add(eventName);

            if (e.getValue() instanceof String target) {
                transitions.add(new StateMachineModel.TransitionDef(
                    fromState, eventName, target, null));
            } else if (e.getValue() instanceof Map<?, ?> map) {
                var target = String.valueOf(map.get("to"));
                var guard = map.get("when") != null
                    ? String.valueOf(map.get("when")) : null;
                transitions.add(new StateMachineModel.TransitionDef(
                    fromState, eventName, target, guard));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<StateMachineModel.EventDef> parseEventDefs(
            Map<String, Object> eventsSection) {
        if (eventsSection == null) return List.of();
        var result = new ArrayList<StateMachineModel.EventDef>();
        for (var entry : eventsSection.entrySet()) {
            var fields = new LinkedHashMap<String, String>();
            if (entry.getValue() instanceof Map<?, ?> eventMap) {
                var fieldsMap = (Map<String, Object>) eventMap.get("fields");
                if (fieldsMap != null) {
                    for (var f : fieldsMap.entrySet()) {
                        fields.put(f.getKey(), String.valueOf(f.getValue()));
                    }
                }
            }
            result.add(new StateMachineModel.EventDef(entry.getKey(), fields));
        }
        return result;
    }
}
