package io.casehub.platform.model;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.ModelEnricher;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@McpDomain("models")
@ApplicationScoped
public class ModelRegistryEnricher implements ModelEnricher {

    @Inject
    ModelRegistry registry;

    @Override
    public String summary() {
        return "Model registry — query available LLM models across vendors, tiers, and capabilities";
    }

    @Override
    public Map<String, Object> state() {
        var all = registry.all();
        var vendors = all.stream().map(ModelDescriptor::vendor).distinct().sorted().toList();
        return Map.of(
            "modelCount", all.size(),
            "vendors", vendors
        );
    }
}
