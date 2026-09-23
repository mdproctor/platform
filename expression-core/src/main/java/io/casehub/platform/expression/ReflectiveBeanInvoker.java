package io.casehub.platform.expression;

import io.casehub.platform.api.expression.BeanInvoker;
import io.casehub.platform.api.expression.InvocationDeniedException;
import io.casehub.platform.api.expression.InvocationPolicy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;

public class ReflectiveBeanInvoker implements BeanInvoker {

    private final Function<String, Object> beanResolver;
    private final InvocationPolicy policy;

    public ReflectiveBeanInvoker(Function<String, Object> beanResolver, InvocationPolicy policy) {
        this.beanResolver = beanResolver;
        this.policy = policy;
    }

    @Override
    public Object invoke(String beanClassName, String methodName, Object... args) {
        if (!policy.isAllowed(beanClassName, methodName)) {
            throw new InvocationDeniedException(beanClassName, methodName);
        }
        Object bean = beanResolver.apply(beanClassName);
        Method method = resolveMethod(bean.getClass(), methodName, args.length);
        try {
            if (method.isVarArgs()) {
                return invokeVarargs(bean, method, args);
            }
            return method.invoke(bean, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException("Invocation failed: " + beanClassName + "::" + methodName, e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Method not accessible: " + beanClassName + "::" + methodName, e);
        }
    }

    private Method resolveMethod(Class<?> beanClass, String methodName, int argCount) {
        Method fixedCandidate = null;
        Method varArgsCandidate = null;
        for (Method m : beanClass.getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (m.isVarArgs() && argCount >= m.getParameterCount() - 1) {
                varArgsCandidate = m;
                continue;
            }
            if (m.getParameterCount() == argCount) {
                fixedCandidate = m;
            }
        }
        if (fixedCandidate != null) return fixedCandidate;
        if (varArgsCandidate != null) return varArgsCandidate;
        throw new IllegalArgumentException(
                "No method '" + methodName + "' with " + argCount + " args on " + beanClass.getName());
    }

    private Object invokeVarargs(Object bean, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
        int fixedCount = method.getParameterCount() - 1;
        Class<?> varArgType = method.getParameterTypes()[fixedCount].getComponentType();
        Object varArgs = java.lang.reflect.Array.newInstance(varArgType, args.length - fixedCount);
        for (int i = 0; i < args.length - fixedCount; i++) {
            java.lang.reflect.Array.set(varArgs, i, args[fixedCount + i]);
        }
        Object[] invokeArgs = new Object[fixedCount + 1];
        System.arraycopy(args, 0, invokeArgs, 0, fixedCount);
        invokeArgs[fixedCount] = varArgs;
        return method.invoke(bean, invokeArgs);
    }
}
