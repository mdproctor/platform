package io.casehub.platform.api.model;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ModelDescriptor(
    String id,
    String apiModelId,
    String backendKey,
    String backendInstanceId,
    String vendor,
    String family,
    String displayName,
    ModelTier tier,
    Set<String> capabilities,
    int contextWindow,
    int maxOutput,
    ModelLocality locality,
    CostTier costTier,
    String authMethod,
    Map<String, String> properties
) {
    public ModelDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(apiModelId, "apiModelId");
        Objects.requireNonNull(backendKey, "backendKey");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(locality, "locality");
        capabilities = capabilities != null ? Set.copyOf(capabilities) : Set.of();
        properties = properties != null ? Map.copyOf(properties) : Map.of();
    }
}
