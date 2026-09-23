package io.casehub.platform.expression;

import io.casehub.platform.api.expression.BeanInvoker;

public class NoOpBeanInvoker implements BeanInvoker {

    @Override
    public Object invoke(String beanClassName, String methodName, Object... args) {
        throw new UnsupportedOperationException(
                "BeanInvoker not available — add casehub-platform-expression to the classpath");
    }
}
