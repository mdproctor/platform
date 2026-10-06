package io.casehub.yaml.core.playbook;

import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaFilteredRegistryTest {

    private static final io.casehub.yaml.plugin.api.Action NOOP =
            (p, s) -> Result.of(Map.of());

    private MapPlaybookSchemaRegistry schemas;
    private SimplePluginRegistry delegate;
    private SchemaFilteredRegistry filtered;

    @BeforeEach
    void setUp() {
        schemas = new MapPlaybookSchemaRegistry();
        schemas.register(PlaybookSchemaDescriptor.domain(
                "clinical-server", "server", Set.of("clinical-trial")));

        delegate = new SimplePluginRegistry();
        delegate.register(Definition.of("rest-call")
                .capability("steps").execute(NOOP).build());
        delegate.register(Definition.of("correlate")
                .capability("correlation").execute(NOOP).build());
        delegate.register(Definition.of("clinical-enroll")
                .capability("clinical-trial").execute(NOOP).build());
        delegate.register(Definition.of("spotlight")
                .capability("spotlight").execute(NOOP).build());

        filtered = SchemaFilteredRegistry.forSchema(
                delegate, schemas, "clinical-server");
    }

    @Test
    void resolve_allowsMatchingCapability() {
        assertThat(filtered.resolve("rest-call")).isPresent();
        assertThat(filtered.resolve("correlate")).isPresent();
        assertThat(filtered.resolve("clinical-enroll")).isPresent();
    }

    @Test
    void resolve_filtersNonMatchingCapability() {
        assertThat(filtered.resolve("spotlight")).isEmpty();
    }

    @Test
    void availableActions_onlyListsMatchingPlugins() {
        assertThat(filtered.availableActions())
                .contains("rest-call", "correlate", "clinical-enroll")
                .doesNotContain("spotlight");
    }

    @Test
    void register_delegatesToUnderlying() {
        filtered.register(Definition.of("new-step")
                .capability("steps").execute(NOOP).build());
        assertThat(delegate.resolve("new-step")).isPresent();
    }

    @Test
    void forSchema_bridgesSchemaAndPluginRegistries() {
        var clientFiltered = SchemaFilteredRegistry.forSchema(
                delegate, schemas, "client");
        assertThat(clientFiltered.resolve("spotlight")).isPresent();
        assertThat(clientFiltered.resolve("correlate")).isEmpty();
    }

    static class SimplePluginRegistry implements PluginRegistry {
        private final ConcurrentHashMap<String, Definition> defs = new ConcurrentHashMap<>();
        @Override public void register(Definition d) { defs.put(d.name(), d); }
        @Override public Optional<Definition> resolve(String name) {
            return Optional.ofNullable(defs.get(name));
        }
        @Override public Set<String> availableActions() { return defs.keySet(); }
    }
}
