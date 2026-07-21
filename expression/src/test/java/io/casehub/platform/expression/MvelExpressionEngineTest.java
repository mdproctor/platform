package io.casehub.platform.expression;

import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.expression.ExpressionCompilationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MvelExpressionEngineTest {

    private final MvelExpressionEngine engine = new MvelExpressionEngine();

    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> MAP_TYPE =
            (Class<Map<String, Object>>) (Class<?>) Map.class;

    @Test
    void type_returnsMvel() {
        assertThat(engine.type()).isEqualTo("mvel");
    }

    @Test
    void compile_mapContext_arithmeticExpression() {
        CompiledExpression<Map<String, Object>, Integer> expr =
                engine.compile("x + y", MAP_TYPE, Integer.class);
        assertThat(expr.eval(Map.of("x", 3, "y", 5))).isEqualTo(8);
    }

    @Test
    void compile_mapContext_booleanExpression() {
        CompiledExpression<Map<String, Object>, Boolean> expr =
                engine.compile("age > 20", MAP_TYPE, Boolean.class);
        assertThat(expr.eval(Map.of("age", 25))).isTrue();
    }

    @Test
    void compile_mapContext_stringEquality() {
        CompiledExpression<Map<String, Object>, Boolean> expr =
                engine.compile("name == \"Alice\"", MAP_TYPE, Boolean.class);
        assertThat(expr.eval(Map.of("name", "Alice"))).isTrue();
        assertThat(expr.eval(Map.of("name", "Bob"))).isFalse();
    }

    @Test
    void compile_withVariables_parameterizedExpression() {
        CompiledExpression<Map<String, Object>, Boolean> expr =
                engine.compile("name == $p0", MAP_TYPE, Boolean.class,
                               Map.of("$p0", "Alice"));
        assertThat(expr.eval(Map.of("name", "Alice"))).isTrue();
        assertThat(expr.eval(Map.of("name", "Bob"))).isFalse();
    }

    @Test
    void compile_booleanLogic() {
        CompiledExpression<Map<String, Object>, Boolean> expr =
                engine.compile("age > 18 && name != \"Bob\"", MAP_TYPE, Boolean.class);
        assertThat(expr.eval(Map.of("age", 25, "name", "Alice"))).isTrue();
        assertThat(expr.eval(Map.of("age", 25, "name", "Bob"))).isFalse();
        assertThat(expr.eval(Map.of("age", 16, "name", "Alice"))).isFalse();
    }

    @Test
    void compile_invalidExpression_throwsCompilationException() {
        CompiledExpression<Map<String, Object>, Boolean> expr =
                engine.compile("!!invalid!!", MAP_TYPE, Boolean.class);
        assertThatThrownBy(() -> expr.eval(Map.of("x", 1)))
                .isInstanceOf(ExpressionCompilationException.class);
    }

    @Test
    void validate_validExpression_noException() {
        engine.validate("age > 20");
    }

    @Test
    void validate_blankExpression_throwsCompilationException() {
        assertThatThrownBy(() -> engine.validate("  "))
                .isInstanceOf(ExpressionCompilationException.class);
    }

    @Test
    void compile_cachedForSameExpression() {
        CompiledExpression<?, ?> first  = engine.compile("x + y", MAP_TYPE, Integer.class);
        CompiledExpression<?, ?> second = engine.compile("x + y", MAP_TYPE, Integer.class);
        assertThat(first).isSameAs(second);
    }

    @Test
    void compile_differentExpressions_notCached() {
        CompiledExpression<?, ?> first  = engine.compile("x + y", MAP_TYPE, Integer.class);
        CompiledExpression<?, ?> second = engine.compile("x - y", MAP_TYPE, Integer.class);
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void compile_pojoContext_fieldAccess() {
        CompiledExpression<TestPerson, String> expr =
                engine.compile("name", TestPerson.class, String.class);
        assertThat(expr.eval(new TestPerson("Alice", 30))).isEqualTo("Alice");
    }

    @Test
    void compile_pojoContext_booleanExpression() {
        CompiledExpression<TestPerson, Boolean> expr =
                engine.compile("age > 20", TestPerson.class, Boolean.class);
        assertThat(expr.eval(new TestPerson("Alice", 30))).isTrue();
        assertThat(expr.eval(new TestPerson("Bob", 15))).isFalse();
    }

    @Test
    void compile_pojoContext_withVariables() {
        CompiledExpression<TestPerson, Boolean> expr =
                engine.compile("name == $p0", TestPerson.class, Boolean.class,
                               Map.of("$p0", "Alice"));
        assertThat(expr.eval(new TestPerson("Alice", 30))).isTrue();
        assertThat(expr.eval(new TestPerson("Bob", 30))).isFalse();
    }

    @Test
    void compile_pojoContext_cachedSeparatelyFromMap() {
        CompiledExpression<?, ?> mapExpr  = engine.compile("age > 20", MAP_TYPE, Boolean.class);
        CompiledExpression<?, ?> pojoExpr = engine.compile("age > 20", TestPerson.class, Boolean.class);
        assertThat(mapExpr).isNotSameAs(pojoExpr);
    }

    @Test
    void compile_blockExpression_mapContext() {
        CompiledExpression<Map<String, Object>, Integer> expr =
                engine.compile("var threshold = 10; return x + threshold;", MAP_TYPE, Integer.class);
        assertThat(expr.eval(Map.of("x", 5))).isEqualTo(15);
    }

    @Test
    void compile_blockExpression_withControlFlow() {
        CompiledExpression<Map<String, Object>, String> expr =
                engine.compile(
                        "var result = \"unknown\"; if (age > 18) { result = \"adult\"; } else { result = \"minor\"; } return result;",
                        MAP_TYPE, String.class);
        assertThat(expr.eval(Map.of("age", 25))).isEqualTo("adult");
        assertThat(expr.eval(Map.of("age", 10))).isEqualTo("minor");
    }

    @Test
    void compile_blockExpression_pojoContext() {
        CompiledExpression<TestPerson, String> expr =
                engine.compile("var greeting = \"Hello \"; return greeting + name;",
                               TestPerson.class, String.class);
        assertThat(expr.eval(new TestPerson("Alice", 30))).isEqualTo("Hello Alice");
    }

    @Test
    void compile_singleExpression_noSemicolon_stillWorks() {
        CompiledExpression<Map<String, Object>, Integer> expr =
                engine.compile("x + y", MAP_TYPE, Integer.class);
        assertThat(expr.eval(Map.of("x", 3, "y", 5))).isEqualTo(8);
    }
}
