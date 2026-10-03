package io.casehub.yaml.step.eval;

import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.plugin.api.ServiceRegistry;
import io.casehub.yaml.step.catalog.ResolvedStep;

import java.util.Map;
import java.util.Objects;

public class CatalogStepRunner implements StepRunner {

    private final PluginRegistry registry;
    private final ServiceRegistry services;

    public CatalogStepRunner(PluginRegistry registry, ServiceRegistry services) {
        this.registry = Objects.requireNonNull(registry);
        this.services = Objects.requireNonNull(services);
    }

    @Override
    public Result run(ResolvedStep step, VariableResolver resolver) {
        if (step instanceof ResolvedStep.PluginStep ps) {
            var definition = registry.resolve(ps.actionName())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No action registered for '" + ps.actionName() + "'"));
            Map<String, Object> resolved = resolver.resolveMap(ps.params(), "step");
            return definition.action().execute(resolved, services);
        }
        throw new IllegalArgumentException(
                "Unsupported step type: " + step.getClass().getSimpleName());
    }
}
