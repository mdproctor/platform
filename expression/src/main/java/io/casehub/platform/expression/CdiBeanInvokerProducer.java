package io.casehub.platform.expression;

import io.casehub.platform.api.expression.InvocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;

@ApplicationScoped
public class CdiBeanInvokerProducer {

    @Produces
    @ApplicationScoped
    public ReflectiveBeanInvoker cdiBeanInvoker(BeanManager beanManager, InvocationPolicy policy) {
        return new ReflectiveBeanInvoker(className -> {
            try {
                Class<?> beanClass = Class.forName(className);
                var beans = beanManager.getBeans(beanClass);
                if (beans.isEmpty()) throw new IllegalArgumentException("No CDI bean for: " + className);
                var bean = beanManager.resolve(beans);
                var ctx = beanManager.createCreationalContext(bean);
                return beanManager.getReference(bean, beanClass, ctx);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Class not found: " + className, e);
            }
        }, policy);
    }
}
