package io.casehub.platform.subscription.rest;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;

import java.io.IOException;

public class ExpressionEvaluatorSerializer extends JsonSerializer<ExpressionEvaluator> {

    @Override
    public void serialize(ExpressionEvaluator value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField("type", value.type());
        gen.writeStringField("expression", extractExpression(value));
        gen.writeEndObject();
    }

    private static String extractExpression(ExpressionEvaluator evaluator) {
        if (evaluator instanceof MvelExpressionEvaluator m) return m.expression();
        if (evaluator instanceof JQExpressionEvaluator j) return j.expression();
        throw new IllegalArgumentException("Unknown evaluator type: " + evaluator.type());
    }
}
