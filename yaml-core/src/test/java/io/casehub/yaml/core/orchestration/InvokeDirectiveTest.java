package io.casehub.yaml.core.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InvokeDirectiveTest {

    @Test
    void parseShorthandBeanAndMethod() {
        var directive = InvokeDirective.parse("io.casehub.trading.AlertRepository::save");
        assertEquals("io.casehub.trading.AlertRepository", directive.beanClassName());
        assertEquals("save", directive.methodName());
        assertTrue(directive.args().isEmpty());
    }

    @Test
    void parseFullFormWithArgs() {
        var directive = InvokeDirective.parse(Map.of(
                "bean", "io.casehub.trading.OrderService",
                "method", "place",
                "args", List.of("${price}", "${quantity}")
        ));
        assertEquals("io.casehub.trading.OrderService", directive.beanClassName());
        assertEquals("place", directive.methodName());
        assertEquals(List.of("${price}", "${quantity}"), directive.args());
    }

    @Test
    void parseFullFormWithoutArgs() {
        var directive = InvokeDirective.parse(Map.of(
                "bean", "io.casehub.trading.OrderService",
                "method", "cancelAll"
        ));
        assertEquals("io.casehub.trading.OrderService", directive.beanClassName());
        assertEquals("cancelAll", directive.methodName());
        assertTrue(directive.args().isEmpty());
    }

    @Test
    void parseRejectsInvalidShorthandWithoutSeparator() {
        assertThrows(IllegalArgumentException.class,
                () -> InvokeDirective.parse("not-a-valid-invoke"));
    }

    @Test
    void parseRejectsNullInput() {
        assertThrows(NullPointerException.class, () -> InvokeDirective.parse(null));
    }

    @Test
    void parseRejectsMapMissingBean() {
        assertThrows(IllegalArgumentException.class,
                () -> InvokeDirective.parse(Map.of("method", "save")));
    }

    @Test
    void parseRejectsMapMissingMethod() {
        assertThrows(IllegalArgumentException.class,
                () -> InvokeDirective.parse(Map.of("bean", "Foo")));
    }

    @Test
    void parsePassesThroughExistingInstance() {
        var original = new InvokeDirective("Foo", "bar", List.of());
        assertSame(original, InvokeDirective.parse(original));
    }

    @Test
    void recordFieldsAreImmutable() {
        var directive = InvokeDirective.parse("Foo::bar");
        assertThrows(UnsupportedOperationException.class,
                () -> directive.args().add("x"));
    }

    @Test
    void parseShorthandWithNestedClassName() {
        var directive = InvokeDirective.parse("io.casehub.Order$Item::validate");
        assertEquals("io.casehub.Order$Item", directive.beanClassName());
        assertEquals("validate", directive.methodName());
    }
}
