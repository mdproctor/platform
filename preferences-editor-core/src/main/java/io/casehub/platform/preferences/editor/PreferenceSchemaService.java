package io.casehub.platform.preferences.editor;

import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;
import io.casehub.platform.api.preferences.PreferenceSchemaRegistry;

import java.util.Comparator;
import java.util.List;

public class PreferenceSchemaService {

    private final PreferenceSchemaRegistry registry;

    public PreferenceSchemaService(PreferenceSchemaRegistry registry) {
        this.registry = registry;
    }

    public SchemaResult schema(String namespace) {
        List<PreferenceSchemaDescriptor> result = registry.discover().stream()
                .filter(d -> namespace == null || namespace.isBlank() || d.namespace().equals(namespace))
                .sorted(Comparator.comparing(PreferenceSchemaDescriptor::qualifiedName))
                .toList();
        return new SchemaResult(result, String.valueOf(registry.version()));
    }
}
