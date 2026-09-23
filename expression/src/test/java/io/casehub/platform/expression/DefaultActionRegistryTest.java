package io.casehub.platform.expression;

import io.casehub.yaml.core.orchestration.ActionHandle;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultActionRegistryTest {

    @Test
    void resolvesRegisteredAction() {
        var registry = new DefaultActionRegistry();
        ActionHandle handle = (scope, args) -> "executed";
        registry.register("test-action", handle);

        var resolved = registry.resolve("test-action");
        assertTrue(resolved.isPresent());
        assertEquals("executed", resolved.get().invoke(null, Map.of()));
    }

    @Test
    void returnsEmptyForUnknownAction() {
        var registry = new DefaultActionRegistry();
        assertTrue(registry.resolve("unknown").isEmpty());
    }

    @Test
    void registeredNamesReturnsAllNames() {
        var registry = new DefaultActionRegistry();
        registry.register("action-a", (s, a) -> null);
        registry.register("action-b", (s, a) -> null);

        var names = registry.registeredNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("action-a"));
        assertTrue(names.contains("action-b"));
    }

    @Test
    void duplicateNameThrowsOnRegister() {
        var registry = new DefaultActionRegistry();
        registry.register("dup", (s, a) -> null);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("dup", (s, a) -> null));
    }
}
