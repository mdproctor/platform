package io.casehub.yaml.step.catalog;

import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeStepCatalogTest {

    private Definition definition(String name) {
        return new Definition(name, null, Map.of(), Map.of(), Portability.JAVA,
                (params, services) -> Result.of(Map.of()), null);
    }

    @Test
    void resolvesRegisteredAction() {
        var registry = new CompositePluginRegistry();
        registry.register(definition("assess-risk"));

        assertThat(registry.resolve("assess-risk")).isPresent();
        assertThat(registry.resolve("assess-risk").get().name()).isEqualTo("assess-risk");
    }

    @Test
    void resolveMissingActionReturnsEmpty() {
        var registry = new CompositePluginRegistry();

        assertThat(registry.resolve("nonexistent")).isEmpty();
    }

    @Test
    void availableActionsReturnsAllKeys() {
        var registry = new CompositePluginRegistry();
        registry.register(definition("action-a"));
        registry.register(definition("action-b"));

        assertThat(registry.availableActions()).containsExactlyInAnyOrder("action-a", "action-b");
    }

    @Test
    void firstRegistrationWinsOnNameCollision() {
        var registry = new CompositePluginRegistry();
        registry.register(definition("shared"));
        registry.register(new Definition("shared", "second", Map.of(), Map.of(), Portability.JAVA,
                (params, services) -> Result.failed("should not be used"), null));

        assertThat(registry.resolve("shared")).isPresent();
        assertThat(registry.resolve("shared").get().description()).isNull();
    }
}
