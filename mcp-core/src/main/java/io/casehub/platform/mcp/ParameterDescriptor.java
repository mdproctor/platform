package io.casehub.platform.mcp;

import java.util.Map;

public record ParameterDescriptor(
        String name,
        String typeName,
        boolean required,
        String description,
        Map<String, String> fields) {

    public ParameterDescriptor(String name, String typeName, boolean required) {
        this(name, typeName, required, "", Map.of());
    }
}
