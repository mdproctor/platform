package io.casehub.platform.api.model;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import java.util.List;

@McpDomain("models")
public interface ModelRegistryApi {

    @PlatformQuery("List available models — filter by vendor, family, tier, locality, or cost tier. All params optional.")
    List<ModelDescriptor> listModels(String vendor, String family,
                                     String tier, String locality,
                                     String maxCostTier);

    @PlatformQuery("Get detailed model info by registry ID")
    ModelDescriptor getModel(String modelId);

    @PlatformMutation("Force refresh from all model sources — returns what changed")
    RefreshResult refreshRegistry();
}
