package io.casehub.platform.expression;

import io.casehub.platform.api.expression.ScenarioAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import io.quarkus.runtime.Startup;

import java.lang.reflect.Method;

@ApplicationScoped
public class CdiActionRegistryProducer {

    @Produces
    @ApplicationScoped
    @Startup
    public DefaultActionRegistry cdiActionRegistry(BeanManager beanManager) {
        var registry = new DefaultActionRegistry();
        for (var beanType : beanManager.getBeans(Object.class)) {
            Class<?> beanClass = beanType.getBeanClass();
            for (Method method : beanClass.getMethods()) {
                ScenarioAction annotation = method.getAnnotation(ScenarioAction.class);
                if (annotation == null) continue;
                String actionName = annotation.value();
                registry.register(actionName, (scope, args) -> {
                    Object bean = CDI.current().select(beanClass).get();
                    try {
                        return method.invoke(bean, scope, args);
                    } catch (Exception e) {
                        throw new RuntimeException("Action '" + actionName + "' failed", e);
                    }
                });
            }
        }
        return registry;
    }
}
