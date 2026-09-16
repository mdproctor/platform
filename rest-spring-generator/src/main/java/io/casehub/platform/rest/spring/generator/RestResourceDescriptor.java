package io.casehub.platform.rest.spring.generator;

import java.util.List;

public record RestResourceDescriptor(
        String className,
        String path,
        String delegateTypeName,
        String delegateFieldName,
        List<RestMethodDescriptor> methods,
        String[] classConsumes,
        String[] classProduces,
        boolean hasContextHeaders
) {}
