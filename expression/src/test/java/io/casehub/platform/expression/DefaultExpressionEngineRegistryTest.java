package io.casehub.platform.expression;

import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.expression.ExpressionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultExpressionEngineRegistryTest {

    private DefaultExpressionEngineRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultExpressionEngineRegistry(java.util.List.of());
    }

    @Test
    void resolve_unknownType_returnsEmpty() {
        assertThat(registry.resolve("unknown")).isEmpty();
    }

    @Test
    void register_andResolve() {
        var engine = new StubExpressionEngine("test");
        registry.register(engine);
        assertThat(registry.resolve("test")).contains(engine);
    }

    @Test
    void compile_unknownType_throwsIllegalArgument() {
        assertThatThrownBy(() -> registry.compile("unknown", "x", Object.class, Boolean.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void compile_delegatesToEngine() {
        var engine = new StubExpressionEngine("test");
        registry.register(engine);
        CompiledExpression<Object, Boolean> compiled =
                registry.compile("test", "expr", Object.class, Boolean.class);
        assertThat(compiled.eval("anything")).isTrue();
    }

    @Test
    void compileWithVariables_delegatesToEngine() {
        var engine = new StubExpressionEngine("test");
        registry.register(engine);
        CompiledExpression<Object, Boolean> compiled =
                registry.compile("test", "expr", Object.class, Boolean.class, Map.of("k", "v"));
        assertThat(compiled.eval("anything")).isTrue();
    }

    @Test
    void validate_unknownType_throwsIllegalArgument() {
        assertThatThrownBy(() -> registry.validate("unknown", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_delegatesToEngine() {
        var engine = new StubExpressionEngine("test");
        registry.register(engine);
        registry.validate("test", "expr");
    }

    private static class StubExpressionEngine implements ExpressionEngine {
        private final String type;
        StubExpressionEngine(String type) { this.type = type; }

        @Override public String type() { return type; }

        @Override
        public <C, R> CompiledExpression<C, R> compile(
                String expression, Class<C> contextType, Class<R> resultType) {
            return new CompiledExpression<>() {
                @Override public String type() { return StubExpressionEngine.this.type; }
                @Override @SuppressWarnings("unchecked")
                public R eval(C context) { return (R) Boolean.TRUE; }
            };
        }

        @Override
        public <C, R> CompiledExpression<C, R> compile(
                String expression, Class<C> contextType, Class<R> resultType,
                Map<String, Object> variables) {
            return compile(expression, contextType, resultType);
        }

        @Override public void validate(String expression) {}
    }
}
