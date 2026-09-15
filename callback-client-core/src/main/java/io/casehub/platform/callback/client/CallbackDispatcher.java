package io.casehub.platform.callback.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CallbackDispatcher {

    private final ObjectMapper objectMapper;
    private final Map<String, Object> spiRegistry = new ConcurrentHashMap<>();

    public CallbackDispatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void registerSpi(String spiName, Object bean) {
        spiRegistry.put(spiName, bean);
    }

    public DispatchResult dispatch(String spiName, String methodName,
                                   String spiHeader, JsonNode argsNode) {
        if (spiHeader == null || spiHeader.isBlank()) {
            return DispatchResult.forbidden("Missing X-CaseHub-SPI header");
        }
        Object bean = spiRegistry.get(spiName);
        if (bean == null) {
            return DispatchResult.notFound("No SPI registered for: " + spiName);
        }
        try {
            int argCount = (argsNode != null && argsNode.isArray()) ? argsNode.size() : 0;
            Method method = findMethod(bean.getClass(), methodName, argCount);
            if (method == null) {
                return DispatchResult.notFound(
                        "No method '" + methodName + "' with " + argCount + " args on SPI " + spiName);
            }
            Object[] args = deserializeArgs(argsNode, method);
            Object result = method.invoke(bean, args);
            return method.getReturnType() == void.class
                    ? DispatchResult.noContent() : DispatchResult.ok(result);
        } catch (InvocationTargetException e) {
            return DispatchResult.error(e.getCause().getMessage());
        } catch (Exception e) {
            return DispatchResult.error(e.getMessage());
        }
    }

    private Method findMethod(Class<?> clazz, String name, int argCount) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && !m.isSynthetic()
                    && m.getParameterCount() == argCount) {
                return m;
            }
        }
        return null;
    }

    private Object[] deserializeArgs(JsonNode argsNode, Method method) throws Exception {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        if (argsNode == null || argsNode.isNull() || !argsNode.isArray()) {
            return args;
        }
        for (int i = 0; i < paramTypes.length && i < argsNode.size(); i++) {
            args[i] = objectMapper.treeToValue(argsNode.get(i), paramTypes[i]);
        }
        return args;
    }
}
