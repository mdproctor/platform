package io.casehub.platform.callback.spring;

import io.casehub.platform.api.callback.CallbackRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.callback.CallbackInvoker;
import io.casehub.platform.governance.PolicyEnforcer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class CallbackSpringAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CallbackInvoker callbackInvoker(PolicyEnforcer policyEnforcer) {
        return new CallbackInvoker(policyEnforcer);
    }

    @Bean
    static CallbackDecoratorBeanPostProcessor callbackDecoratorBeanPostProcessor(
            ObjectProvider<CallbackRegistry> registryProvider,
            ObjectProvider<CallbackInvoker> invokerProvider,
            ObjectProvider<CurrentPrincipal> principalProvider) {
        return new CallbackDecoratorBeanPostProcessor(
                registryProvider, invokerProvider, principalProvider);
    }
}
