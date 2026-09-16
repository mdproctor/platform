package io.casehub.platform.rest.spring.generator;

import com.palantir.javapoet.TypeName;

import java.util.List;

public record RestMethodDescriptor(
        String methodName,
        String httpMethod,
        String subPath,
        TypeName returnType,
        List<ParameterDescriptor> parameters,
        String[] consumes,
        String[] produces,
        boolean needsHeaderInjection
) {
    public record ParameterDescriptor(
            String name,
            TypeName type,
            ParameterSource source,
            String annotationValue
    ) {}

    public enum ParameterSource {
        PATH, QUERY, HEADER, BODY
    }
}
