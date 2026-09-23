package io.casehub.platform.expression;

import io.casehub.platform.api.expression.InvocationPolicy;

public class NoOpInvocationPolicy implements InvocationPolicy {

    @Override
    public boolean isAllowed(String beanClassName, String methodName) {
        return true;
    }
}
