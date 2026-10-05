package io.casehub.yaml.core.playbook;

import java.util.Collection;
import java.util.Optional;

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
}
