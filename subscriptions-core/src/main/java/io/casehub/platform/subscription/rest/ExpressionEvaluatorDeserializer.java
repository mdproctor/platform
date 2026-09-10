package io.casehub.platform.subscription.rest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;

import java.io.IOException;

public class ExpressionEvaluatorDeserializer extends JsonDeserializer<ExpressionEvaluator> {

    @Override
    public ExpressionEvaluator deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        JsonNode typeNode = node.get("type");
        JsonNode exprNode = node.get("expression");
        if (typeNode == null || exprNode == null) {
            throw new IOException("Filter entry requires 'type' and 'expression' fields");
        }
        String type = typeNode.asText();
        String expression = exprNode.asText();
        return switch (type) {
            case "mvel" -> new MvelExpressionEvaluator(expression);
            case "jq" -> new JQExpressionEvaluator(expression);
            default -> throw new IOException("Unknown expression type: " + type);
        };
    }
}
