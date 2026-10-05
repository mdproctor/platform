package io.casehub.yaml.core.playbook;

import java.util.Map;
import java.util.Objects;

public record PlaybookFrontMatter(String version, String schema, String name, Map<String, Object> metadata) {

    public PlaybookFrontMatter {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(schema, "schema");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public PlaybookFrontMatter(String version, String schema) {
        this(version, schema, null, Map.of());
    }

    public PlaybookFrontMatter(String version, String schema, String name) {
        this(version, schema, name, Map.of());
    }
}
