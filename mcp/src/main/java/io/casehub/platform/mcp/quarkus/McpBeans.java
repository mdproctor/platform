package io.casehub.platform.mcp.quarkus;

import io.casehub.platform.mcp.ModelRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class McpBeans {

    @Produces
    @ApplicationScoped
    public ModelRegistry modelRegistry() {
        return new ModelRegistry();
    }
}
