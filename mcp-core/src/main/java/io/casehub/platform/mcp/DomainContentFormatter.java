package io.casehub.platform.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class DomainContentFormatter {

    private DomainContentFormatter() {}

    public static Map<String, Object> formatIndex(List<DomainModel> domains) {
        List<Map<String, Object>> domainList = domains.stream()
                .map(DomainContentFormatter::domainSummary)
                .collect(Collectors.toList());
        return Map.of("domains", domainList);
    }

    public static Map<String, Object> formatDomain(DomainModel domain) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domain", domain.name());
        if (!domain.summary().isEmpty()) result.put("summary", domain.summary());
        if (!domain.state().isEmpty()) result.put("state", domain.state());

        List<Map<String, Object>> queries = domain.operations().stream()
                .filter(op -> op.type() == OperationDescriptor.OperationType.QUERY)
                .map(DomainContentFormatter::operationToMap)
                .collect(Collectors.toList());
        if (!queries.isEmpty()) result.put("queries", queries);

        List<Map<String, Object>> mutations = domain.operations().stream()
                .filter(op -> op.type() == OperationDescriptor.OperationType.MUTATION)
                .map(DomainContentFormatter::operationToMap)
                .collect(Collectors.toList());
        if (!mutations.isEmpty()) result.put("mutations", mutations);

        List<Map<String, Object>> events = domain.events().stream()
                .map(DomainContentFormatter::eventToMap)
                .collect(Collectors.toList());
        if (!events.isEmpty()) result.put("events", events);

        return result;
    }

    private static Map<String, Object> domainSummary(DomainModel d) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", d.name());
        if (!d.summary().isEmpty()) entry.put("summary", d.summary());
        entry.put("operationCount", d.operations().size());
        if (!d.events().isEmpty()) entry.put("eventCount", d.events().size());
        if (!d.state().isEmpty()) entry.put("state", d.state());
        return entry;
    }

    private static Map<String, Object> operationToMap(OperationDescriptor op) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", op.name());
        if (!op.summary().isEmpty()) map.put("summary", op.summary());
        if (!op.params().isEmpty()) {
            map.put("params", op.params().stream()
                    .map(DomainContentFormatter::paramToMap).collect(Collectors.toList()));
        }
        map.put("returns", op.returnTypeName());
        return map;
    }

    private static Map<String, Object> eventToMap(EventDescriptor event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", event.name());
        if (!event.summary().isEmpty()) map.put("summary", event.summary());
        if (!event.params().isEmpty()) {
            map.put("params", event.params().stream()
                    .map(DomainContentFormatter::paramToMap).collect(Collectors.toList()));
        }
        map.put("delivery", "qhorus");
        map.put("channel", event.channel());
        return map;
    }

    private static Map<String, Object> paramToMap(ParameterDescriptor param) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", param.name());
        map.put("type", param.typeName());
        if (param.required()) map.put("required", true);
        if (!param.description().isEmpty()) map.put("description", param.description());
        if (!param.fields().isEmpty()) map.put("fields", param.fields());
        return map;
    }
}
