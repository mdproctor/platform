package io.casehub.platform.expression.spring;

import io.casehub.platform.expression.DefaultActionRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpringActionRegistryTest {

    @Test
    void registryResolvesAndInvokes() {
        var registry = new DefaultActionRegistry();
        registry.register("greet", (scope, args) -> "hello " + args.get("name"));

        var handle = registry.resolve("greet");
        assertTrue(handle.isPresent());
        assertEquals("hello world", handle.get().invoke(null, Map.of("name", "world")));
    }
}
