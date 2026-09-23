package io.casehub.platform.expression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BeanInvokerSpiTest {

    @Test
    void noOpBeanInvokerThrowsUnsupported() {
        var invoker = new NoOpBeanInvoker();
        assertThrows(UnsupportedOperationException.class,
                () -> invoker.invoke("Foo", "bar"));
    }

    @Test
    void noOpInvocationPolicyAllowsEverything() {
        var policy = new NoOpInvocationPolicy();
        assertTrue(policy.isAllowed("any.Class", "anyMethod"));
    }

    @Test
    void noOpActionRegistryReturnsEmpty() {
        var registry = new NoOpActionRegistry();
        assertTrue(registry.resolve("anything").isEmpty());
        assertTrue(registry.registeredNames().isEmpty());
    }
}
