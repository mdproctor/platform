package io.casehub.yaml.core.playbook;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface PlaybookSchemaRegistry {

    void register(PlaybookSchemaDescriptor descriptor);

    Optional<PlaybookSchemaDescriptor> resolve(String schemaName);

    Collection<PlaybookSchemaDescriptor> all();

    default boolean isKnown(String schemaName) {
        return resolve(schemaName).isPresent();
    }

    default PlaybookSchemaDescriptor resolveOrThrow(String schemaName) {
        return resolve(schemaName).orElseThrow(() ->
                new IllegalArgumentException("Unknown playbook schema: '" + schemaName + "'"));
    }

    default PlaybookSchemaDescriptor resolveEffective(PlaybookFrontMatter frontMatter) {
        if (frontMatter == null) return null;
        return resolveOrThrow(frontMatter.schema());
    }

    default Set<String> effectiveCapabilities(String schemaName) {
        var effective = new java.util.HashSet<String>();
        var visited   = new java.util.HashSet<String>();
        var current   = schemaName;
        while (current != null) {
            if (!visited.add(current)) {
                throw new IllegalStateException(
                        "Circular schema inheritance: " + visited);
            }
            var desc = resolveOrThrow(current);
            effective.addAll(desc.capabilities());
            current = desc.baseSchema();
        }
        return Set.copyOf(effective);
    }
}
