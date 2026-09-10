package io.casehub.platform.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DomainContentFormatterTest {

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
    void formatIndexListsDomains() {
        var result = DomainContentFormatter.formatIndex(List.of(ENGINE, WORK));
        assertThat(result).containsKey("domains");
        var domains = (List<Map<String, Object>>) result.get("domains");
        assertThat(domains).hasSize(2);
        assertThat(domains.get(0)).containsEntry("name", "engine");
        assertThat(domains.get(0)).containsEntry("operationCount", 2);
        assertThat(domains.get(0)).containsEntry("summary", "Engine domain");
    }

    @Test
    @SuppressWarnings("unchecked")
    void formatIndexOmitsEmptySummary() {
        var result = DomainContentFormatter.formatIndex(List.of(WORK));
        var domains = (List<Map<String, Object>>) result.get("domains");
        assertThat(domains.get(0)).doesNotContainKey("summary");
    }

    @Test
    @SuppressWarnings("unchecked")
    void formatDomainIncludesQueriesAndMutations() {
        var result = DomainContentFormatter.formatDomain(ENGINE);
        assertThat(result).containsEntry("domain", "engine");
        var queries = (List<Map<String, Object>>) result.get("queries");
        assertThat(queries).hasSize(1);
        assertThat(queries.get(0)).containsEntry("name", "cases");
        var mutations = (List<Map<String, Object>>) result.get("mutations");
        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).containsEntry("name", "startCase");
    }

    @Test
    void formatDomainOmitsEmptyMutations() {
        var result = DomainContentFormatter.formatDomain(WORK);
        assertThat(result).doesNotContainKey("mutations");
    }

    @Test
    @SuppressWarnings("unchecked")
    void formatDomainIncludesParams() {
        var result = DomainContentFormatter.formatDomain(ENGINE);
        var mutations = (List<Map<String, Object>>) result.get("mutations");
        var startCase = mutations.get(0);
        var params = (List<Map<String, Object>>) startCase.get("params");
        assertThat(params).hasSize(1);
        assertThat(params.get(0)).containsEntry("name", "definitionId");
        assertThat(params.get(0)).containsEntry("required", true);
    }
}
