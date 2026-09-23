package io.casehub.platform.api.expression;

public interface BeanInvoker {
    Object invoke(String beanClassName, String methodName, Object... args);
}
