package io.casehub.platform.model;

import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelRegistryApi;
import io.casehub.platform.api.model.ModelTier;
import io.casehub.platform.api.model.RefreshResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class ModelRegistryService implements ModelRegistryApi {

    private final ModelRegistry registry;
    private final ModelRegistryRefresher refresher;

    @Inject
    ModelRegistryService(ModelRegistry registry, ModelRegistryRefresher refresher) {
        this.registry = registry;
        this.refresher = refresher;
    }

    ModelRegistryService(InMemoryModelRegistry registry, ModelRegistryRefresher refresher) {
        this.registry = registry;
        this.refresher = refresher;
    }

    @Override
    public List<ModelDescriptor> listModels(String vendor, String family,
                                             String tier, String locality,
                                             String maxCostTier) {
        var builder = ModelQuery.builder();
        if (vendor != null && !vendor.isBlank()) builder.vendor(vendor);
        if (family != null && !family.isBlank()) builder.family(family);
        if (tier != null && !tier.isBlank()) builder.tier(ModelTier.valueOf(tier.toUpperCase()));
        if (locality != null && !locality.isBlank()) builder.locality(ModelLocality.valueOf(locality.toUpperCase()));
        if (maxCostTier != null && !maxCostTier.isBlank()) builder.maxCostTier(CostTier.valueOf(maxCostTier.toUpperCase()));
        return registry.query(builder.build());
    }

    @Override
    public ModelDescriptor getModel(String modelId) {
        return registry.resolveById(modelId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + modelId));
    }

    @Override
    public RefreshResult refreshRegistry() {
        return refresher.refreshAllWithResult();
    }
}
