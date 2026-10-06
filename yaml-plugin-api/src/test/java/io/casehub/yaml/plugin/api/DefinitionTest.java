package io.casehub.yaml.plugin.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionTest {

    private static final Action NOOP_ACTION = (params, services) -> Result.of(Map.of());

    @Test
    void builderCreatesDefinition() {
        Definition def = Definition.of("test-action")
                .description("A test action")
                .input("name", ParameterType.STRING, true)
                .output("result", ParameterType.STRING)
                .portability(Portability.JAVA)
                .execute(NOOP_ACTION)
                .build();

        assertEquals("test-action", def.name());
        assertEquals("A test action", def.description());
        assertEquals(1, def.inputs().size());
        assertTrue(def.inputs().get("name").required());
        assertEquals(1, def.outputs().size());
        assertEquals(Portability.JAVA, def.portability());
        assertNotNull(def.action());
    }

    @Test
    void requiresName() {
        assertThrows(IllegalArgumentException.class, () ->
            new Definition(null, null, Map.of(), Map.of(), Portability.JAVA, NOOP_ACTION, null));
    }

    @Test
    void requiresAction() {
        assertThrows(IllegalArgumentException.class, () ->
            new Definition("test", null, Map.of(), Map.of(), Portability.JAVA, null, null));
    }

    @Test
    void defaultsPortabilityToJava() {
        Definition def = Definition.of("test")
                .execute(NOOP_ACTION)
                .build();
        assertEquals(Portability.JAVA, def.portability());
    }


    @Test
    void defaultsCapabilityToSteps() {
        Definition def = Definition.of("test")
                                   .execute(NOOP_ACTION)
                                   .build();
        assertEquals("steps", def.capability());
    }

    @Test
    void nullCapabilityDefaultsToSteps() {
        Definition def = new Definition("test", null, Map.of(), Map.of(),
                                        Portability.JAVA, NOOP_ACTION, null);
        assertEquals("steps", def.capability());
    }

    @Test
    void blankCapabilityDefaultsToSteps() {
        Definition def = new Definition("test", null, Map.of(), Map.of(),
                                        Portability.JAVA, NOOP_ACTION, "  ");
        assertEquals("steps", def.capability());
    }

    @Test
    void explicitCapabilityPreserved() {
        Definition def = Definition.of("correlate")
                                   .capability("correlation")
                                   .execute(NOOP_ACTION)
                                   .build();
        assertEquals("correlation", def.capability());
    }

    @Test
    void inputsAndOutputsAreUnmodifiable() {
        Definition def = Definition.of("test")
                .input("x", ParameterType.STRING, true)
                .execute(NOOP_ACTION)
                .build();
        assertThrows(UnsupportedOperationException.class, () ->
            def.inputs().put("y", new Parameter(ParameterType.STRING, false, null, null, null, null)));
    }
}
