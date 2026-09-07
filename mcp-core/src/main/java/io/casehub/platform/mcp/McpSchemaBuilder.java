package io.casehub.platform.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpSchemaBuilder {

    public Map<String, Object> build(SchemaMode mode, List<DomainModel> domains) {
        return switch (mode) {
            case SIMPLE -> buildSimple(domains);
            case RICH -> buildRich(domains);
        };
    }

    private Map<String, Object> buildSimple(List<DomainModel> domains) {
        List<DomainModel> sorted = domains.stream()
                .sorted(Comparator.comparing(DomainModel::name))
                .toList();

        List<String> domainNames = sorted.stream()
                .map(DomainModel::name)
                .toList();

        StringBuilder operationDesc = new StringBuilder("The operation to execute.");
        for (DomainModel domain : sorted) {
            operationDesc.append("\n\n").append(domain.name()).append(":");
            appendOperationList(operationDesc, domain, OperationDescriptor.OperationType.QUERY, "Queries");
            appendOperationList(operationDesc, domain, OperationDescriptor.OperationType.MUTATION, "Mutations");
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("domain", Map.of(
                "type", "string",
                "enum", domainNames,
                "description", "The target domain"));
        properties.put("operation", Map.of(
                "type", "string",
                "description", operationDesc.toString()));
        properties.put("params", Map.of(
                "type", "string",
                "description", "Operation parameters as a JSON object. Use casehub_model for parameter details."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("domain", "operation"));
        return schema;
    }

    private Map<String, Object> buildRich(List<DomainModel> domains) {
        List<DomainModel> sorted = domains.stream()
                .sorted(Comparator.comparing(DomainModel::name))
                .toList();

        List<String> domainNames = sorted.stream()
                .map(DomainModel::name)
                .toList();

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("domain", Map.of(
                "type", "string",
                "enum", domainNames));
        properties.put("operation", Map.of(
                "type", "string"));
        properties.put("params", Map.of(
                "type", "string",
                "description", "Operation parameters as a JSON object"));

        List<Map<String, Object>> allOf = new ArrayList<>();
        for (DomainModel domain : sorted) {
            List<String> operationNames = domain.operations().stream()
                    .map(OperationDescriptor::name)
                    .toList();

            allOf.add(Map.of(
                    "if", Map.of("properties", Map.of("domain", Map.of("const", domain.name()))),
                    "then", Map.of("properties", Map.of("operation", Map.of("enum", operationNames)))));
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("domain", "operation"));
        schema.put("allOf", allOf);
        return schema;
    }

    private void appendOperationList(StringBuilder sb, DomainModel domain,
                                     OperationDescriptor.OperationType type, String label) {
        List<String> names = domain.operations().stream()
                .filter(op -> op.type() == type)
                .map(OperationDescriptor::name)
                .toList();
        if (!names.isEmpty()) {
            sb.append("\n  ").append(label).append(": ").append(String.join(", ", names));
        }
    }
}
