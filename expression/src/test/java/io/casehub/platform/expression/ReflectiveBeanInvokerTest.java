package io.casehub.platform.expression;

import io.casehub.platform.api.expression.InvocationDeniedException;
import io.casehub.platform.api.expression.InvocationPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ReflectiveBeanInvokerTest {

    public static class Calculator {
        public int add(int a, int b) { return a + b; }
        public String greet() { return "hello"; }
        public int sum(int... numbers) {
            int total = 0;
            for (int n : numbers) total += n;
            return total;
        }
    }

    private final InvocationPolicy allowAll = (bean, method) -> true;
    private final Map<String, Object> beans = new ConcurrentHashMap<>(Map.of(
            "test.Calculator", new Calculator()
    ));
    private final Function<String, Object> resolver = name -> {
        Object bean = beans.get(name);
        if (bean == null) throw new IllegalArgumentException("Bean not found: " + name);
        return bean;
    };

    @Test
    void invokesSimpleMethodWithArgs() {
        var invoker = new ReflectiveBeanInvoker(resolver, allowAll);
        assertEquals(5, invoker.invoke("test.Calculator", "add", 2, 3));
    }

    @Test
    void invokesNoArgMethod() {
        var invoker = new ReflectiveBeanInvoker(resolver, allowAll);
        assertEquals("hello", invoker.invoke("test.Calculator", "greet"));
    }

    @Test
    void invokesVarargsMethod() {
        var invoker = new ReflectiveBeanInvoker(resolver, allowAll);
        assertEquals(10, invoker.invoke("test.Calculator", "sum", 1, 2, 3, 4));
    }

    @Test
    void prefersFixedArityOverVarargs() {
        var invoker = new ReflectiveBeanInvoker(resolver, allowAll);
        assertEquals(7, invoker.invoke("test.Calculator", "add", 3, 4));
    }

    @Test
    void policyDenialThrowsInvocationDenied() {
        InvocationPolicy denyAll = (bean, method) -> false;
        var invoker = new ReflectiveBeanInvoker(resolver, denyAll);
        assertThrows(InvocationDeniedException.class,
                () -> invoker.invoke("test.Calculator", "add", 1, 2));
    }

    @Test
    void unknownBeanThrowsIllegalArgument() {
        var invoker = new ReflectiveBeanInvoker(resolver, allowAll);
        assertThrows(IllegalArgumentException.class,
                () -> invoker.invoke("nonexistent.Bean", "method"));
    }

    @Test
    void unknownMethodThrowsIllegalArgument() {
        var invoker = new ReflectiveBeanInvoker(resolver, allowAll);
        assertThrows(IllegalArgumentException.class,
                () -> invoker.invoke("test.Calculator", "nonexistent"));
    }
}
