package io.casehub.platform.graphql.spring.generator;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;

import java.util.List;

@McpDomain("sample-impl")
public class SampleDomainImpl {

    @PlatformQuery("List all items")
    public List<String> listItems() { return List.of(); }

    @PlatformQuery("Get a single item")
    public String getItem(@PathParam("id") String id) { return id; }

    @PlatformMutation("Create a new item")
    public String createItem(String name) { return name; }
}
