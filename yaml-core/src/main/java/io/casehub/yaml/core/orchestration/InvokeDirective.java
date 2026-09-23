package io.casehub.yaml.core.orchestration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record InvokeDirective(String beanClassName, String methodName, List<String> args) {

    public InvokeDirective {
        Objects.requireNonNull(beanClassName, "beanClassName must not be null");
        Objects.requireNonNull(methodName, "methodName must not be null");
        args = args == null ? List.of() : List.copyOf(args);
    }

    @SuppressWarnings("unchecked")
    public static InvokeDirective parse(Object raw) {
        Objects.requireNonNull(raw, "invoke directive must not be null");
        if (raw instanceof InvokeDirective d) { return d; }
        if (raw instanceof String s) { return parseShorthand(s); }
        if (raw instanceof Map<?, ?> m) { return parseMap((Map<String, Object>) m); }
        throw new IllegalArgumentException(
                "Invalid invoke value: expected string or {bean, method} map — got " + raw.getClass().getSimpleName());
    }

    private static InvokeDirective parseShorthand(String s) {
        int sep = s.lastIndexOf("::");
        if (sep < 0) {
            throw new IllegalArgumentException(
                    "Invalid invoke shorthand: expected 'Bean::method' — got '" + s + "'");
        }
        return new InvokeDirective(s.substring(0, sep), s.substring(sep + 2), List.of());
    }

    @SuppressWarnings("unchecked")
    private static InvokeDirective parseMap(Map<String, Object> m) {
        String bean = (String) m.get("bean");
        String method = (String) m.get("method");
        if (bean == null) {
            throw new IllegalArgumentException("invoke map requires 'bean' field");
        }
        if (method == null) {
            throw new IllegalArgumentException("invoke map requires 'method' field");
        }
        List<String> args = m.containsKey("args") ? (List<String>) m.get("args") : List.of();
        return new InvokeDirective(bean, method, args);
    }
}
