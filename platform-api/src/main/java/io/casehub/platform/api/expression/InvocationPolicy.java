package io.casehub.platform.api.expression;

public interface InvocationPolicy {
    boolean isAllowed(String beanClassName, String methodName);
}
