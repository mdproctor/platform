package io.casehub.yaml.core.playbook;

import java.util.Objects;
import java.util.Set;

public record PlaybookSchemaDescriptor(
        String name,
        String baseSchema,
        Set<String> capabilities
) {

    public PlaybookSchemaDescriptor {
        Objects.requireNonNull(name, "name");
        capabilities = capabilities != null ? Set.copyOf(capabilities) : Set.of();
    }

    public boolean isBuiltIn() {
        return baseSchema == null;
    }

    public boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }

    public static PlaybookSchemaDescriptor builtIn(String name, Set<String> capabilities) {
        return new PlaybookSchemaDescriptor(name, null, capabilities);
    }

    public static PlaybookSchemaDescriptor domain(String name, String baseSchema, Set<String> capabilities) {
        Objects.requireNonNull(baseSchema, "baseSchema");
        return new PlaybookSchemaDescriptor(name, baseSchema, capabilities);
    }
}
