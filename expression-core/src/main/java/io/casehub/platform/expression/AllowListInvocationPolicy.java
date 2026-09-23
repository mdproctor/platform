package io.casehub.platform.expression;

import io.casehub.platform.api.expression.InvocationPolicy;

import java.util.Set;

public class AllowListInvocationPolicy implements InvocationPolicy {

    private final Set<String> allowedPackages;

    public AllowListInvocationPolicy(Set<String> allowedPackages) {
        this.allowedPackages = Set.copyOf(allowedPackages);
    }

    @Override
    public boolean isAllowed(String beanClassName, String methodName) {
        if (allowedPackages.isEmpty()) { return false; }
        for (String prefix : allowedPackages) {
            if (beanClassName.startsWith(prefix)) { return true; }
        }
        return false;
    }
}
