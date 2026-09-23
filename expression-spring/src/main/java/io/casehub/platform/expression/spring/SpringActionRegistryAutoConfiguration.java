package io.casehub.platform.expression.spring;

import io.casehub.platform.api.expression.ScenarioAction;
import io.casehub.platform.expression.DefaultActionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.Map;

@AutoConfiguration
public class SpringActionRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DefaultActionRegistry actionRegistry(ApplicationContext ctx) {
        var registry = new DefaultActionRegistry();
        for (String beanName : ctx.getBeanDefinitionNames()) {
            Class<?> beanType = ctx.getType(beanName);
            if (beanType == null) continue;
            for (Method method : beanType.getMethods()) {
                ScenarioAction annotation = method.getAnnotation(ScenarioAction.class);
                if (annotation == null) continue;
                String actionName = annotation.value();
                registry.register(actionName, (scope, args) -> {
                    Object bean = ctx.getBean(beanName);
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
