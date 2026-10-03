package io.casehub.yaml.statemachine.generator;

public final class StateEnumEmitter {

    private StateEnumEmitter() {}

    public static GeneratedFile emit(StateMachineModel model) {
        var sb = new StringBuilder();
        sb.append("package ").append(model.pkg()).append(";\n\n");
        sb.append("public enum ").append(model.name()).append("State {\n");
        sb.append("    ");
        var stateNames = model.states().stream()
            .map(StateEnumEmitter::toEnumConstant)
            .toList();
        sb.append(String.join(", ", stateNames));
        sb.append("\n}\n");

        return new GeneratedFile(model.name() + "State.java", sb.toString());
    }

    static String toEnumConstant(String yamlState) {
        return yamlState.replace('-', '_').toUpperCase();
    }
}
