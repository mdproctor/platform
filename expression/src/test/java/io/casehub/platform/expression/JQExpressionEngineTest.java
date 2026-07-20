package io.casehub.platform.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.expression.ExpressionCompilationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JQExpressionEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JQExpressionEngine engine = new JQExpressionEngine();

    @Test
    void type_returnsJq() {
        assertThat(engine.type()).isEqualTo("jq");
    }

    @SuppressWarnings("unchecked")
    @Test
    void compile_fieldExtraction_listResult() {
        ObjectNode input = MAPPER.createObjectNode().put("status", "active");
        CompiledExpression<JsonNode, List<JsonNode>> expr =
                engine.compile(".status", JsonNode.class, (Class<List<JsonNode>>) (Class<?>) List.class);
        List<JsonNode> result = expr.eval(input);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).asText()).isEqualTo("active");
    }

    @Test
    void compile_booleanExpression() {
        ObjectNode input = MAPPER.createObjectNode().put("age", 25);
        CompiledExpression<JsonNode, Boolean> expr =
                engine.compile(".age > 20", JsonNode.class, Boolean.class);
        assertThat(expr.eval(input)).isTrue();
    }

    @Test
    void compile_booleanExpression_false() {
        ObjectNode input = MAPPER.createObjectNode().put("age", 15);
        CompiledExpression<JsonNode, Boolean> expr =
                engine.compile(".age > 20", JsonNode.class, Boolean.class);
        assertThat(expr.eval(input)).isFalse();
    }

    @Test
    void compile_invalidExpression_throwsCompilationException() {
        assertThatThrownBy(() -> engine.compile("invalid jq [[[", JsonNode.class, Boolean.class))
                .isInstanceOf(ExpressionCompilationException.class);
    }

    @Test
    void validate_validExpression_noException() {
        engine.validate(".status");
    }

    @Test
    void validate_invalidExpression_throwsCompilationException() {
        assertThatThrownBy(() -> engine.validate("invalid jq [[["))
                .isInstanceOf(ExpressionCompilationException.class);
    }

    @Test
    void compile_cachedForSameExpression() {
        CompiledExpression<?, ?> first = engine.compile(".status", JsonNode.class, Boolean.class);
        CompiledExpression<?, ?> second = engine.compile(".status", JsonNode.class, Boolean.class);
        assertThat(first).isSameAs(second);
    }
// --- Map context adaptation ---

    @Test
    void compile_mapContext_booleanExpression_true() {
        var expr    = engine.compile(".age > 20", Map.class, Boolean.class);
        var context = new java.util.HashMap<String, Object>();
        context.put("age", 25);
        assertThat(expr.eval(context)).isTrue();
    }

    @Test
    void compile_mapContext_booleanExpression_false() {
        var expr    = engine.compile(".age > 20", Map.class, Boolean.class);
        var context = new java.util.HashMap<String, Object>();
        context.put("age", 15);
        assertThat(expr.eval(context)).isFalse();
    }

    @Test
    void compile_mapContext_nullValues() {
        var expr    = engine.compile(".status == \"active\"", Map.class, Boolean.class);
        var context = new java.util.HashMap<String, Object>();
        context.put("status", null);
        assertThat(expr.eval(context)).isFalse();
    }

    @Test
    void compile_mapContext_stringComparison() {
        var expr    = engine.compile(".severity == \"HIGH\"", Map.class, Boolean.class);
        var context = new java.util.HashMap<String, Object>();
        context.put("severity", "HIGH");
        assertThat(expr.eval(context)).isTrue();
    }

    @Test
    void compile_mapContext_cachedSeparatelyFromJsonNode() {
        CompiledExpression<?, ?> mapExpr  = engine.compile(".status", Map.class, Boolean.class);
        CompiledExpression<?, ?> jsonExpr = engine.compile(".status", JsonNode.class, Boolean.class);
        assertThat(mapExpr).isNotSameAs(jsonExpr);
    }

    @Test
    void compile_mapContext_cachedForSameExpression() {
        CompiledExpression<?, ?> first  = engine.compile(".status", Map.class, Boolean.class);
        CompiledExpression<?, ?> second = engine.compile(".status", Map.class, Boolean.class);
        assertThat(first).isSameAs(second);
    }

    @SuppressWarnings("unchecked")
    @Test
    void compile_mapContext_listResult() {
        var expr    = engine.compile(".status", Map.class, (Class<List<JsonNode>>) (Class<?>) List.class);
        var context = new java.util.HashMap<String, Object>();
        context.put("status", "active");
        var result = expr.eval(context);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).asText()).isEqualTo("active");
    }

    @Test
    void compile_jsonNodeContext_unchanged() {
        ObjectNode input = MAPPER.createObjectNode().put("age", 25);
        CompiledExpression<JsonNode, Boolean> expr =
                engine.compile(".age > 20", JsonNode.class, Boolean.class);
        assertThat(expr.eval(input)).isTrue();
    }


    @Test
    void compile_stringResult() {
        ObjectNode input = MAPPER.createObjectNode().put("name", "alice");
        CompiledExpression<JsonNode, String> expr =
                engine.compile(".name", JsonNode.class, String.class);
        assertThat(expr.eval(input)).isEqualTo("alice");
    }

    @Test
    void compile_integerResult() {
        ObjectNode input = MAPPER.createObjectNode().put("count", 42);
        CompiledExpression<JsonNode, Integer> expr =
                engine.compile(".count", JsonNode.class, Integer.class);
        assertThat(expr.eval(input)).isEqualTo(42);
    }

    @Test
    void compile_stringResult_nullField_returnsNull() {
        ObjectNode input = MAPPER.createObjectNode();
        input.putNull("name");
        CompiledExpression<JsonNode, String> expr =
                engine.compile(".name", JsonNode.class, String.class);
        assertThat(expr.eval(input)).isNull();
    }

    @Test
    void compile_stringResult_missingField_returnsNull() {
        ObjectNode input = MAPPER.createObjectNode().put("other", "value");
        CompiledExpression<JsonNode, String> expr =
                engine.compile(".name", JsonNode.class, String.class);
        assertThat(expr.eval(input)).isNull();
    }

    @Test
    void compile_mapContext_stringResult() {
        var expr    = engine.compile(".name", Map.class, String.class);
        var context = new java.util.HashMap<String, Object>();
        context.put("name", "alice");
        assertThat(expr.eval(context)).isEqualTo("alice");
    }

    @Test
    void compile_scalarResult_cachedForSameExpression() {
        CompiledExpression<?, ?> first  = engine.compile(".name", JsonNode.class, String.class);
        CompiledExpression<?, ?> second = engine.compile(".name", JsonNode.class, String.class);
        assertThat(first).isSameAs(second);
    }

    @Test
    void compile_differentResultTypes_produceDifferentInstances() {
        CompiledExpression<?, ?> stringExpr = engine.compile(".value", JsonNode.class, String.class);
        CompiledExpression<?, ?> intExpr    = engine.compile(".value", JsonNode.class, Integer.class);
        assertThat(stringExpr).isNotSameAs(intExpr);
    }
}
