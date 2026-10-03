package io.casehub.yaml.statemachine.generator;

import java.util.stream.Collectors;

public final class EventEmitter {

    private EventEmitter() {}

    public static GeneratedFile emit(StateMachineModel model) {
        var sb = new StringBuilder();
        sb.append("package ").append(model.pkg()).append(";\n\n");

        var eventName = model.name() + "Event";
        var permits = model.events().stream()
            .map(e -> eventName + "." + toPascalCase(e.name()))
            .collect(Collectors.joining(", "));

        sb.append("public sealed interface ").append(eventName).append("\n");
        sb.append("    permits ").append(permits).append(" {\n\n");

        for (var event : model.events()) {
            var recordName = toPascalCase(event.name());
            var fields = event.fields().entrySet().stream()
                .map(f -> mapType(f.getValue()) + " " + f.getKey())
                .collect(Collectors.joining(", "));
            sb.append("    record ").append(recordName)
              .append("(").append(fields).append(")")
              .append(" implements ").append(eventName).append(" {}\n");
        }

        sb.append("}\n");

        return new GeneratedFile(eventName + ".java", sb.toString());
    }

    static String toPascalCase(String kebab) {
        var parts = kebab.split("[-_]");
        var sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    static String mapType(String yamlType) {
        return switch (yamlType) {
            case "string" -> "String";
            case "integer" -> "int";
            case "number" -> "double";
            case "boolean" -> "boolean";
            default -> "Object";
        };
    }
}
