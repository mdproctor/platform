package io.casehub.platform.expression;

import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.expression.ExpressionCompilationException;
import io.casehub.platform.api.expression.ExpressionEngine;
import io.casehub.platform.api.expression.ExpressionEvaluationException;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class JexlExpressionEngine implements ExpressionEngine {

    private static final JexlEngine JEXL = new JexlBuilder()
            .strict(false).silent(false).create();

    private final ConcurrentHashMap<CacheKey, CompiledExpression<?, ?>> cache = new ConcurrentHashMap<>();

    @Override public String type() { return "jexl"; }

    @Override
    public <C, R> CompiledExpression<C, R> compile(String expression, Class<C> contextType, Class<R> resultType) {
        return compile(expression, contextType, resultType, Map.of());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C, R> CompiledExpression<C, R> compile(String expression, Class<C> contextType, Class<R> resultType, Map<String, Object> variables) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(contextType, "contextType");
        Objects.requireNonNull(resultType, "resultType");
        Map<String, Object> boundVars = variables.isEmpty() ? Map.of() : Map.copyOf(variables);
        var key = new CacheKey(expression, contextType, resultType, boundVars);
        return (CompiledExpression<C, R>) cache.computeIfAbsent(key, k -> new JexlCompiledExpression<>(expression, resultType, boundVars));
    }

    @Override
    public void validate(String expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression.isBlank()) throw new ExpressionCompilationException("JEXL expression must not be blank");
        try { JEXL.createExpression(expression); }
        catch (JexlException e) { throw new ExpressionCompilationException("Failed to compile JEXL expression: " + expression, e); }
    }

    private static class JexlCompiledExpression<R> implements CompiledExpression<Map<String, Object>, R> {
        private final JexlExpression compiled;
        private final Class<R> resultType;
        private final Map<String, Object> boundVars;

        JexlCompiledExpression(String expression, Class<R> resultType, Map<String, Object> boundVars) {
            this.resultType = resultType;
            this.boundVars = boundVars;
            try { this.compiled = JEXL.createExpression(expression); }
            catch (JexlException e) { throw new ExpressionCompilationException("Failed to compile JEXL expression: " + expression, e); }
        }

        @Override public String type() { return "jexl"; }

        @Override
        @SuppressWarnings("unchecked")
        public R eval(Map<String, Object> context) {
            var jexlCtx = new MapContext();
            context.forEach(jexlCtx::set);
            boundVars.forEach(jexlCtx::set);
            try { return (R) compiled.evaluate(jexlCtx); }
            catch (JexlException e) { throw new ExpressionEvaluationException("JEXL evaluation failed for: " + compiled.getSourceText(), e); }
        }
    }

    private record CacheKey(String expression, Class<?> contextType, Class<?> resultType, Map<String, Object> variables) {}
}
