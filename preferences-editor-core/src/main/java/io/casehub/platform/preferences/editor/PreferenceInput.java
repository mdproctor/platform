package io.casehub.platform.preferences.editor;

public record PreferenceInput(String namespace, String name, String subKey, String value) {
    public PreferenceInput {
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (value == null) throw new IllegalArgumentException("value is required");
        if (subKey == null) subKey = "";
    }
}
