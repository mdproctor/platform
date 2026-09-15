package io.casehub.platform.preferences.editor;

import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreferenceSchemaServiceTest {

    private static final PreferenceSchemaDescriptor DESC_B = new PreferenceSchemaDescriptor(
            "b-ns", "key1", "b-ns.key1", "STRING", "Key 1", "", "default", false, Map.of(), List.of());
    private static final PreferenceSchemaDescriptor DESC_A = new PreferenceSchemaDescriptor(
            "a-ns", "key2", "a-ns.key2", "STRING", "Key 2", "", "default", false, Map.of(), List.of());

    private final InMemoryPreferenceSchemaRegistry registry = new InMemoryPreferenceSchemaRegistry();

    {
        registry.register(DESC_B);
        registry.register(DESC_A);
    }

    @Test
    void schema_returns_sorted_by_qualifiedName() {
        var service = new PreferenceSchemaService(registry);
        var result = service.schema(null);
        assertThat(result.schemas()).extracting(PreferenceSchemaDescriptor::qualifiedName)
                .containsExactly("a-ns.key2", "b-ns.key1");
        assertThat(result.version()).isNotBlank();
    }

    @Test
    void schema_filters_by_namespace() {
        var service = new PreferenceSchemaService(registry);
        var result = service.schema("a-ns");
        assertThat(result.schemas()).hasSize(1);
        assertThat(result.schemas().get(0).namespace()).isEqualTo("a-ns");
    }

    @Test
    void schema_returns_all_when_namespace_blank() {
        var service = new PreferenceSchemaService(registry);
        var result = service.schema("");
        assertThat(result.schemas()).hasSize(2);
    }
}
