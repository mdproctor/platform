package io.casehub.platform.callback.spring;

import io.casehub.platform.api.callback.CallbackRegistration;
import io.casehub.platform.api.callback.CallbackRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.callback.CallbackInvoker;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

class CallbackInvocationHandler implements InvocationHandler {

    private final Object delegate;
    private final String spiName;
    private final boolean fanOut;
    private final Supplier<CallbackRegistry> registrySupplier;
    private final Supplier<CallbackInvoker> invokerSupplier;
    private final Supplier<CurrentPrincipal> principalSupplier;

    CallbackInvocationHandler(Object delegate,
                              String spiName,
                              boolean fanOut,
                              Supplier<CallbackRegistry> registrySupplier,
                              Supplier<CallbackInvoker> invokerSupplier,
                              Supplier<CurrentPrincipal> principalSupplier) {
        this.delegate = delegate;
        this.spiName = spiName;
        this.fanOut = fanOut;
        this.registrySupplier = registrySupplier;
        this.invokerSupplier = invokerSupplier;
        this.principalSupplier = principalSupplier;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(delegate, args);
        }

        String tenancyId = principalSupplier.get().tenancyId();
        List<CallbackRegistration> registrations =
                registrySupplier.get().findBySpi(spiName, tenancyId);

        if (registrations.isEmpty()) {
            return method.invoke(delegate, args);
        }

        boolean isVoid = method.getReturnType() == void.class;
        Object[] invokeArgs = args != null ? args : new Object[0];

        if (fanOut) {
            for (CallbackRegistration reg : registrations) {
                Object result = invokerSupplier.get().invoke(
                        reg, method.getName(), invokeArgs, method.getReturnType());
                if (!isVoid && result != null) {
                    return result;
                }
            }
            if (isVoid) {
                return null;
            }
            return method.invoke(delegate, args);
        } else {
            CallbackRegistration reg = registrations.get(0);
            if (isVoid) {
                invokerSupplier.get().invoke(reg, method.getName(), invokeArgs, void.class);
                return null;
            }
            return invokerSupplier.get().invoke(
                    reg, method.getName(), invokeArgs, method.getReturnType());
        }
    }
}
