package io.casehub.yaml.step.catalog;

import io.casehub.yaml.plugin.api.Action;
import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.Execute;
import io.casehub.yaml.plugin.api.Optional;
import io.casehub.yaml.plugin.api.Parameter;
import io.casehub.yaml.plugin.api.ParameterType;
import io.casehub.yaml.plugin.api.Plugin;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.plugin.api.Required;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.plugin.api.ServiceRegistry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

public class PluginScanner {

    public void scanAndRegister(Class<?> pluginClass, PluginRegistry registry) {
        Plugin annotation = pluginClass.getAnnotation(Plugin.class);
        if (annotation == null) {
            throw new IllegalArgumentException(pluginClass.getName() + " is not annotated with @Plugin");
        }
        if (!pluginClass.isRecord()) {
            throw new IllegalArgumentException(pluginClass.getName() + " must be a record");
        }

        String name = annotation.value();
        String description = annotation.description().isEmpty() ? null : annotation.description();
        Portability portability = annotation.portability();
        String capability = annotation.capability();

        Method executeMethod = findExecuteMethod(pluginClass);
        Map<String, Parameter> inputs = buildInputs(pluginClass);

        Action action = createAction(pluginClass, executeMethod, inputs);
        registry.register(new Definition(name, description, inputs, Map.of(), portability, action, capability));
    }

    private Method findExecuteMethod(Class<?> pluginClass) {
        for (Method m : pluginClass.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Execute.class)) {
                return m;
            }
        }
        throw new IllegalArgumentException(pluginClass.getName() + " has no @Execute method");
    }

    private Map<String, Parameter> buildInputs(Class<?> pluginClass) {
        Map<String, Parameter> inputs = new LinkedHashMap<>();
        for (RecordComponent rc : pluginClass.getRecordComponents()) {
            String key = toKebabCase(rc.getName());
            ParameterType type = mapType(rc.getType());
            boolean required = rc.isAnnotationPresent(Required.class)
                    || rc.getType().isPrimitive();
            boolean optional = rc.isAnnotationPresent(Optional.class);
            if (optional) required = false;

            inputs.put(key, new Parameter(type, required, null, null, null, null));
        }
        return inputs;
    }

    private Action createAction(Class<?> pluginClass, Method executeMethod,
                                Map<String, Parameter> inputs) {
        return (params, services) -> {
            try {
                RecordComponent[] components = pluginClass.getRecordComponents();
                Object[] args = new Object[components.length];
                Class<?>[] ctorTypes = new Class<?>[components.length];

                for (int i = 0; i < components.length; i++) {
                    ctorTypes[i] = components[i].getType();
                    String key = toKebabCase(components[i].getName());
                    args[i] = extractParam(params, key, components[i].getType());
                }

                Constructor<?> ctor = pluginClass.getDeclaredConstructor(ctorTypes);
                Object instance = ctor.newInstance(args);

                Class<?>[] execParamTypes = executeMethod.getParameterTypes();
                Object[] execArgs = new Object[execParamTypes.length];
                for (int i = 0; i < execParamTypes.length; i++) {
                    if (services != null) {
                        execArgs[i] = services.lookup(execParamTypes[i]);
                    }
                }

                return (Result) executeMethod.invoke(instance, execArgs);
            } catch (Exception e) {
                return Result.failed("Plugin execution failed: " + e.getMessage());
            }
        };
    }

    private Object extractParam(Map<String, Object> params, String key, Class<?> type) {
        Object value = params.get(key);
        if (value == null) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == boolean.class) return false;
            return null;
        }
        if (type == int.class || type == Integer.class) {
            return ((Number) value).intValue();
        }
        if (type == long.class || type == Long.class) {
            return ((Number) value).longValue();
        }
        if (type == double.class || type == Double.class) {
            return ((Number) value).doubleValue();
        }
        return value;
    }

    static ParameterType mapType(Class<?> type) {
        if (type == String.class) return ParameterType.STRING;
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) return ParameterType.INTEGER;
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) return ParameterType.NUMBER;
        if (type == boolean.class || type == Boolean.class) return ParameterType.BOOLEAN;
        if (java.util.List.class.isAssignableFrom(type)) return ParameterType.ARRAY;
        if (java.util.Map.class.isAssignableFrom(type)) return ParameterType.OBJECT;
        return ParameterType.OBJECT;
    }

    static String toKebabCase(String camelCase) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('-');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
