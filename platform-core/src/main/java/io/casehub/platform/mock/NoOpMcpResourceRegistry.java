package io.casehub.platform.mock;

import io.casehub.platform.api.mcp.McpResourceDescriptor;
import io.casehub.platform.api.mcp.McpResourceHandle;
import io.casehub.platform.api.mcp.McpResourceHandler;
import io.casehub.platform.api.mcp.McpResourceRegistration;
import io.casehub.platform.api.mcp.McpResourceRegistry;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class NoOpMcpResourceRegistry implements McpResourceRegistry {

    private static final McpResourceHandle NOOP_HANDLE = new McpResourceHandle() {
        @Override public void notifyUpdate(String uri) {}
        @Override public void deregister() {}
    };

    @Override public McpResourceRegistration newResource(McpResourceDescriptor descriptor) { return new NoOpRegistration(); }
    @Override public void deregister(String name) {}
    @Override public Optional<McpResourceDescriptor> resolve(String name) { return Optional.empty(); }
    @Override public List<McpResourceDescriptor> list() { return List.of(); }

    private static class NoOpRegistration implements McpResourceRegistration {
        @Override public McpResourceRegistration handler(McpResourceHandler handler) { return this; }
        @Override public McpResourceRegistration completion(String argumentName, Supplier<List<String>> values) { return this; }
        @Override public McpResourceRegistration serverName(String serverName) { return this; }
        @Override public McpResourceHandle register() { return NOOP_HANDLE; }
    }
}
