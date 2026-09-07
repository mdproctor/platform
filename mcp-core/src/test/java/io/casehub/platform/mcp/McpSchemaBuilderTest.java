package io.casehub.platform.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpSchemaBuilderTest {

    private final McpSchemaBuilder builder = new McpSchemaBuilder();

    private static final DomainModel ENGINE = new DomainModel("engine", "Engine domain",
            List.of(
                    new OperationDescriptor("cases", OperationDescriptor.OperationType.QUERY,
                            "List cases", List.of(), "CaseList", null, null),
                    new OperationDescriptor("startCase", OperationDescriptor.OperationType.MUTATION,
                            "Start a case", List.of(new ParameterDescriptor("definitionId", "String", true)),
                            "Case", null, null)),
            List.of(), Map.of());

    private static final DomainModel WORK = new DomainModel("work", "",
            List.of(
                    new OperationDescriptor("workItems", OperationDescriptor.OperationType.QUERY,
                            "", List.of(), "WorkItemList", null, null)),
            List.of(), Map.of());

    @Test
    @SuppressWarnings("unchecked")
    void simpleModeBuildsDomainEnum() {
        Map<String, Object> schema = builder.build(SchemaMode.SIMPLE, List.of(ENGINE, WORK));

        assertThat(schema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> domainProp = (Map<String, Object>) properties.get("domain");
        assertThat(domainProp).containsEntry("type", "string");
        List<String> domainEnum = (List<String>) domainProp.get("enum");
        assertThat(domainEnum).containsExactly("engine", "work");
    }

    @Test
    @SuppressWarnings("unchecked")
    void simpleModeDescriptionListsOperations() {
        Map<String, Object> schema = builder.build(SchemaMode.SIMPLE, List.of(ENGINE, WORK));

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> operationProp = (Map<String, Object>) properties.get("operation");
        String description = (String) operationProp.get("description");

        assertThat(description).contains("engine:");
        assertThat(description).contains("Queries: cases");
        assertThat(description).contains("Mutations: startCase");
        assertThat(description).contains("work:");
        assertThat(description).contains("Queries: workItems");
    }

    @Test
    @SuppressWarnings("unchecked")
    void simpleModeDomainsAreSorted() {
        Map<String, Object> schema = builder.build(SchemaMode.SIMPLE, List.of(WORK, ENGINE));

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> domainProp = (Map<String, Object>) properties.get("domain");
        List<String> domainEnum = (List<String>) domainProp.get("enum");
        assertThat(domainEnum).containsExactly("engine", "work");
    }

    @Test
    @SuppressWarnings("unchecked")
    void simpleModeRequiresDomainAndOperation() {
        Map<String, Object> schema = builder.build(SchemaMode.SIMPLE, List.of(ENGINE));

        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactly("domain", "operation");
    }

    @Test
    @SuppressWarnings("unchecked")
    void richModeBuildsIfThenPerDomain() {
        Map<String, Object> schema = builder.build(SchemaMode.RICH, List.of(ENGINE, WORK));

        assertThat(schema).containsKey("allOf");
        List<Map<String, Object>> allOf = (List<Map<String, Object>>) schema.get("allOf");
        assertThat(allOf).hasSize(2);

        Map<String, Object> engineClause = allOf.get(0);
        Map<String, Object> ifPart = (Map<String, Object>) engineClause.get("if");
        Map<String, Object> ifProps = (Map<String, Object>) ifPart.get("properties");
        Map<String, Object> domainConst = (Map<String, Object>) ifProps.get("domain");
        assertThat(domainConst).containsEntry("const", "engine");

        Map<String, Object> thenPart = (Map<String, Object>) engineClause.get("then");
        Map<String, Object> thenProps = (Map<String, Object>) thenPart.get("properties");
        Map<String, Object> opEnum = (Map<String, Object>) thenProps.get("operation");
        List<String> operations = (List<String>) opEnum.get("enum");
        assertThat(operations).containsExactly("cases", "startCase");
    }

    @Test
    @SuppressWarnings("unchecked")
    void richModeHasNoDomainDescription() {
        Map<String, Object> schema = builder.build(SchemaMode.RICH, List.of(ENGINE));

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> domainProp = (Map<String, Object>) properties.get("domain");
        assertThat(domainProp).doesNotContainKey("description");
    }

    @Test
    void emptyDomainsProducesValidSchema() {
        Map<String, Object> schema = builder.build(SchemaMode.SIMPLE, List.of());

        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).containsKey("properties");
    }

    @Test
    @SuppressWarnings("unchecked")
    void simpleModeOmitsMutationsLabelWhenNone() {
        Map<String, Object> schema = builder.build(SchemaMode.SIMPLE, List.of(WORK));

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> operationProp = (Map<String, Object>) properties.get("operation");
        String description = (String) operationProp.get("description");

        assertThat(description).contains("Queries: workItems");
        assertThat(description).doesNotContain("Mutations");
    }
}
