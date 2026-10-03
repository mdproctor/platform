package io.casehub.yaml.step.eval;

import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.plugin.api.ServiceRegistry;
import io.casehub.yaml.step.catalog.ResolvedStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogStepRunnerTest {

    private PluginRegistry registry;
    private ServiceRegistry services;
    private VariableResolver resolver;

    @BeforeEach
    void setUp() {
        registry = new SimplePluginRegistry();
        services = new MapServiceRegistry();
        resolver = new VariableResolver(Map.of(), Set.of());
    }

    @Test
    void pluginStep_resolvesAndExecutes() {
        registry.register(Definition.of("greet")
                .execute((params, svc) -> Result.of(Map.of("greeting", "hello " + params.get("name"))))
                .build());

        var step = new ResolvedStep.PluginStep(null, "greet", Map.of("name", "world"), Map.of());
        var runner = new CatalogStepRunner(registry, services);

        var result = runner.run(step, resolver);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).containsEntry("greeting", "hello world");
    }

    @Test
    void pluginStep_resolvesVariablesBeforeExecution() {
        registry.register(Definition.of("echo")
                .execute((params, svc) -> Result.of(params))
                .build());

        var varResolver = new VariableResolver(Map.of("env", key -> "resolved-value"), Set.of());

        var step = new ResolvedStep.PluginStep(null, "echo", Map.of("val", "${env.test}"), Map.of());
        var runner = new CatalogStepRunner(registry, services);

        var result = runner.run(step, varResolver);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).containsEntry("val", "resolved-value");
    }

    @Test
    void unknownAction_throwsIllegalArgument() {
        var step = new ResolvedStep.PluginStep(null, "missing", Map.of(), Map.of());
        var runner = new CatalogStepRunner(registry, services);

        assertThatThrownBy(() -> runner.run(step, resolver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void nonPluginStep_throwsIllegalArgument() {
        var invoke = new ResolvedStep.InvokeStep(null, Map.of("mcp", "tool"), Map.of());
        var runner = new CatalogStepRunner(registry, services);

        assertThatThrownBy(() -> runner.run(invoke, resolver))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passesServiceRegistryToAction() {
        var svc = new MapServiceRegistry();
        svc.register(String.class, "injected-service");

        registry.register(Definition.of("use-service")
                .execute((params, s) -> Result.of(Map.of("svc", s.lookup(String.class))))
                .build());

        var step = new ResolvedStep.PluginStep(null, "use-service", Map.of(), Map.of());
        var runner = new CatalogStepRunner(registry, svc);

        var result = runner.run(step, resolver);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).containsEntry("svc", "injected-service");
    }

    private static class SimplePluginRegistry implements PluginRegistry {
        private final Map<String, Definition> defs = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void register(Definition definition) {
            defs.put(definition.name(), definition);
        }

        @Override
        public Optional<Definition> resolve(String actionName) {
            return Optional.ofNullable(defs.get(actionName));
        }

        @Override
        public Set<String> availableActions() {
            return defs.keySet();
        }
    }
}
