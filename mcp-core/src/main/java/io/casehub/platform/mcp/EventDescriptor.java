package io.casehub.platform.mcp;

import java.util.List;

public record EventDescriptor(
        String name,
        String summary,
        List<ParameterDescriptor> params,
        String channel) {}
