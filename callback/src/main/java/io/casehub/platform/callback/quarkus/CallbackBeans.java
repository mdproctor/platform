package io.casehub.platform.callback.quarkus;

import io.casehub.platform.callback.CallbackInvoker;
import io.casehub.platform.governance.PolicyEnforcer;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class CallbackBeans {

    private CallbackInvoker invoker;

    @Produces
    @ApplicationScoped
    public CallbackInvoker callbackInvoker(PolicyEnforcer policyEnforcer) {
        invoker = new CallbackInvoker(policyEnforcer);
        return invoker;
    }

    @PreDestroy
    void shutdown() {
        if (invoker != null) {
            invoker.close();
        }
    }
}
