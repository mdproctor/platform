package io.casehub.platform.expression;

import io.casehub.platform.api.expression.ExpressionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpressionDefaultsTest {

    @Test
    void conditionDefaultsToMvel() {
        var registry = new DefaultExpressionEngineRegistry(List.of());
        assertEquals("mvel", registry.resolveDefault(ExpressionContext.CONDITION));
    }

    @Test
    void transformDefaultsToJq() {
        var registry = new DefaultExpressionEngineRegistry(List.of());
        assertEquals("jq", registry.resolveDefault(ExpressionContext.TRANSFORM));
    }

    @Test
    void filterDefaultsToJq() {
        var registry = new DefaultExpressionEngineRegistry(List.of());
        assertEquals("jq", registry.resolveDefault(ExpressionContext.FILTER));
    }

    @Test
    void defaultsAreOverridable() {
        var registry = new DefaultExpressionEngineRegistry(List.of());
        registry.registerDefault(ExpressionContext.CONDITION, "jq");
        assertEquals("jq", registry.resolveDefault(ExpressionContext.CONDITION));
    }
}
