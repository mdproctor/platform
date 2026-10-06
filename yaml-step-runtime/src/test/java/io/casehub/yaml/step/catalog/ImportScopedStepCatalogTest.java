package io.casehub.yaml.step.catalog;

import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportScopedStepCatalogTest {

    private Definition defn(String name) {
        return new Definition(name, null, Map.of(), Map.of(), Portability.JAVA,
                (params, services) -> Result.of(Map.of()), null);
    }

    private PluginRegistry globalRegistry() {
        var registry = new CompositePluginRegistry();
        registry.register(defn("global-action"));
        return registry;
    }

    @Test
    void importScopedShadowsGlobal() {
        var importedDef = defn("local-override");
        var scoped = new ImportScopedStepCatalog(
                Map.of("global-action", importedDef), globalRegistry());

        assertThat(scoped.resolve("global-action")).isPresent();
        assertThat(scoped.resolve("global-action").get().name())
                .isEqualTo("local-override");
    }

    @Test
    void fallsBackToGlobalForUnimportedActions() {
        var scoped = new ImportScopedStepCatalog(
                Map.of("local-only", defn("local-only")), globalRegistry());

        assertThat(scoped.resolve("global-action")).isPresent();
        assertThat(scoped.resolve("global-action").get().name())
                .isEqualTo("global-action");
    }

    @Test
    void availableActionsIncludesBothScopes() {
        var scoped = new ImportScopedStepCatalog(
                Map.of("local-only", defn("local-only")), globalRegistry());

        assertThat(scoped.availableActions())
                .containsExactlyInAnyOrder("local-only", "global-action");
    }

    @Test
    void resolveMissingFromBothReturnsEmpty() {
        var scoped = new ImportScopedStepCatalog(Map.of(), globalRegistry());

        assertThat(scoped.resolve("nonexistent")).isEmpty();
    }
}
