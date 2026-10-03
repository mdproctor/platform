package io.casehub.yaml.statemachine.generator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

public final class DispatchEmitter {

    private DispatchEmitter() {}

    public static GeneratedFile emit(StateMachineModel model) {
        var sb = new StringBuilder();
        var stateEnum = model.name() + "State";
        var eventType = model.name() + "Event";
        var className = model.name() + "Dispatch";

        sb.append("package ").append(model.pkg()).append(";\n\n");
        sb.append("import io.casehub.yaml.core.orchestration.DefaultOrcStateMachine;\n");
        sb.append("import io.casehub.yaml.core.orchestration.OrcStateMachine;\n\n");

        sb.append("public final class ").append(className).append(" {\n\n");

        sb.append("    private final OrcStateMachine<")
          .append(stateEnum).append("> sm;\n\n");

        sb.append("    private ").append(className)
          .append("(OrcStateMachine<").append(stateEnum).append("> sm) {\n");
        sb.append("        this.sm = sm;\n");
        sb.append("    }\n\n");

        emitFactory(sb, model, stateEnum, className);

        sb.append("    public static ").append(className)
          .append(" wrapping(OrcStateMachine<").append(stateEnum)
          .append("> target) {\n");
        sb.append("        return new ").append(className)
          .append("(target);\n");
        sb.append("    }\n\n");

        sb.append("    public ").append(className)
          .append(" targeting(OrcStateMachine<").append(stateEnum)
          .append("> newTarget) {\n");
        sb.append("        return new ").append(className)
          .append("(newTarget);\n");
        sb.append("    }\n\n");

        emitFire(sb, model, stateEnum, eventType);

        sb.append("    public ").append(stateEnum)
          .append(" currentState() { return sm.currentState(); }\n\n");
        sb.append("    public OrcStateMachine<").append(stateEnum)
          .append("> delegate() { return sm; }\n");

        sb.append("}\n");

        return new GeneratedFile(className + ".java", sb.toString());
    }

    private static void emitFactory(StringBuilder sb,
            StateMachineModel model, String stateEnum, String className) {
        var initialState = StateEnumEmitter.toEnumConstant(
            model.states().getFirst());

        sb.append("    public static ").append(className)
          .append(" create() {\n");
        sb.append("        var sm = DefaultOrcStateMachine.<")
          .append(stateEnum).append(">builder(\"")
          .append(model.name().toLowerCase()).append("\", ")
          .append(stateEnum).append(".").append(initialState)
          .append(")\n");

        for (var t : model.transitions()) {
            if (t.eventName() == null) continue;
            sb.append("            .transition(")
              .append(stateEnum).append(".")
              .append(StateEnumEmitter.toEnumConstant(t.fromState()))
              .append(", ").append(stateEnum).append(".")
              .append(StateEnumEmitter.toEnumConstant(t.toState()))
              .append(")\n");
        }

        if (!model.terminalStates().isEmpty()) {
            var terminals = model.terminalStates().stream()
                .map(s -> stateEnum + "." + StateEnumEmitter.toEnumConstant(s))
                .collect(Collectors.joining(", "));
            sb.append("            .terminal(").append(terminals).append(")\n");
        }

        sb.append("            .build();\n");
        sb.append("        return new ").append(className)
          .append("(sm);\n");
        sb.append("    }\n\n");
    }

    private static void emitFire(StringBuilder sb,
            StateMachineModel model, String stateEnum, String eventType) {
        sb.append("    public boolean fire(").append(eventType)
          .append(" event) {\n");
        sb.append("        return switch (event) {\n");

        var byEvent = new LinkedHashMap<String,
            List<StateMachineModel.TransitionDef>>();
        for (var t : model.transitions()) {
            if (t.eventName() != null) {
                byEvent.computeIfAbsent(t.eventName(),
                    k -> new ArrayList<>()).add(t);
            }
        }

        for (var event : model.events()) {
            var recordName = EventEmitter.toPascalCase(event.name());
            var transitions = byEvent.getOrDefault(event.name(), List.of());
            var pv = String.valueOf(Character.toLowerCase(recordName.charAt(0)));

            for (var t : transitions) {
                var fromEnum = stateEnum + "."
                    + StateEnumEmitter.toEnumConstant(t.fromState());
                var toEnum = stateEnum + "."
                    + StateEnumEmitter.toEnumConstant(t.toState());

                sb.append("            case ").append(eventType)
                  .append(".").append(recordName).append(" ").append(pv);

                if (t.guard() != null) {
                    var compiled = GuardCompiler.compile(
                        t.guard(), pv, event.fields());
                    sb.append("\n                when sm.currentState() == ")
                      .append(fromEnum).append(" && ").append(compiled);
                } else {
                    sb.append("\n                when sm.currentState() == ")
                      .append(fromEnum);
                }

                sb.append("\n                -> sm.transition(")
                  .append(fromEnum).append(", ").append(toEnum)
                  .append(", ").append(pv).append(");\n");
            }

            sb.append("            case ").append(eventType)
              .append(".").append(recordName).append(" ")
              .append(pv).append("\n                -> false;\n");
        }

        sb.append("        };\n");
        sb.append("    }\n\n");
    }
}
