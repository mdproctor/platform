package io.casehub.platform.expression.spring;

import io.casehub.platform.expression.AllowListInvocationPolicy;
import io.casehub.platform.expression.ReflectiveBeanInvoker;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpringBeanInvokerTest {

    public static class TestService {
        public String hello() { return "world"; }
    }

    @Test
    void invokesViaApplicationContextResolver() {
        var policy = new AllowListInvocationPolicy(Set.of("io.casehub"));
        var service = new TestService();
        var invoker = new ReflectiveBeanInvoker(
                name -> name.equals(TestService.class.getName()) ? service : null,
                policy
        );
        assertEquals("world", invoker.invoke(TestService.class.getName(), "hello"));
    }
}
