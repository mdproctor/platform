package io.casehub.platform.mcp;

import java.lang.reflect.Method;
import java.util.List;

public record OperationDescriptor(
        String name,
        OperationType type,
        String summary,
        List<ParameterDescriptor> params,
        String returnTypeName,
        Method method,
        Class<?> resolverClass) {

    public enum OperationType { QUERY, MUTATION }
}
