package io.casehub.platform.callback.spring;

import io.casehub.platform.api.callback.CallbackRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.CallbackEligible;
import io.casehub.platform.callback.CallbackInvoker;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import java.lang.reflect.Proxy;
import java.util.logging.Logger;

class CallbackDecoratorBeanPostProcessor implements BeanPostProcessor, Ordered {

    private static final Logger LOG = Logger.getLogger(CallbackDecoratorBeanPostProcessor.class.getName());

    private final ObjectProvider<CallbackRegistry> registryProvider;
    private final ObjectProvider<CallbackInvoker> invokerProvider;
    private final ObjectProvider<CurrentPrincipal> principalProvider;

    CallbackDecoratorBeanPostProcessor(ObjectProvider<CallbackRegistry> registryProvider,
                                       ObjectProvider<CallbackInvoker> invokerProvider,
                                       ObjectProvider<CurrentPrincipal> principalProvider) {
        this.registryProvider = registryProvider;
        this.invokerProvider = invokerProvider;
        this.principalProvider = principalProvider;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> eligibleInterface = findCallbackEligibleInterface(bean.getClass());
        if (eligibleInterface == null) {
            return bean;
        }

        CallbackEligible ann = eligibleInterface.getAnnotation(CallbackEligible.class);
        String spiName = ann.name().isEmpty()
                ? toKebabCase(eligibleInterface.getSimpleName())
                : ann.name();

        LOG.info("Wrapping bean '" + beanName + "' with callback decorator for SPI '" + spiName + "'");

        return Proxy.newProxyInstance(
                eligibleInterface.getClassLoader(),
                new Class<?>[]{eligibleInterface},
                new CallbackInvocationHandler(
                        bean, spiName, ann.fanOut(),
                        registryProvider::getObject,
                        invokerProvider::getObject,
                        principalProvider::getObject));
    }

    private static Class<?> findCallbackEligibleInterface(Class<?> clazz) {
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.isAnnotationPresent(CallbackEligible.class)) {
                return iface;
            }
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            Class<?> found = findCallbackEligibleInterface(iface);
            if (found != null) return found;
        }
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            return findCallbackEligibleInterface(superclass);
        }
        return null;
    }

    static String toKebabCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) return camelCase;

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    boolean prevUpper = Character.isUpperCase(camelCase.charAt(i - 1));
                    boolean nextLower = (i + 1 < camelCase.length())
                            && Character.isLowerCase(camelCase.charAt(i + 1));
                    if (!prevUpper || nextLower) {
                        result.append('-');
                    }
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
