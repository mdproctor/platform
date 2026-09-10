package io.casehub.platform.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelTest {

    @Test
    void countsOperationsByType() {
        var query = new OperationDescriptor("caseById",
                OperationDescriptor.OperationType.QUERY, "Get case",
                List.of(), "CaseInstance", null, null);
        var mutation = new OperationDescriptor("startCase",
                OperationDescriptor.OperationType.MUTATION, "Start case",
                List.of(), "CaseInstance", null, null);

        var model = new DomainModel("engine", "Engine",
                List.of(query, mutation), List.of(), Map.of());

        assertThat(model.queryCount()).isEqualTo(1);
        assertThat(model.mutationCount()).isEqualTo(1);
        assertThat(model.operations()).hasSize(2);
    }

    @Test
    void emptyModel() {
        var model = new DomainModel("empty", "", List.of(), List.of(), Map.of());
        assertThat(model.queryCount()).isZero();
        assertThat(model.mutationCount()).isZero();
        assertThat(model.events()).isEmpty();
        assertThat(model.state()).isEmpty();
    }

    @Test
    void parameterDescriptorDefaults() {
        var param = new ParameterDescriptor("id", "UUID", true);
        assertThat(param.description()).isEmpty();
        assertThat(param.fields()).isEmpty();
    }

    @Test
    void eventDescriptorFields() {
        var event = new EventDescriptor("caseLifecycle", "Case state changes",
                List.of(new ParameterDescriptor("caseId", "UUID", true)),
                "engine-case-lifecycle");
        assertThat(event.name()).isEqualTo("caseLifecycle");
        assertThat(event.channel()).isEqualTo("engine-case-lifecycle");
        assertThat(event.params()).hasSize(1);
    }
}
