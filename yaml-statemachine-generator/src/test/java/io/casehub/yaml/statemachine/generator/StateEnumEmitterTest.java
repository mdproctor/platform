package io.casehub.yaml.statemachine.generator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StateEnumEmitterTest {

    @Test
    void emit_generatesEnumWithAllStates() {
        var model = new StateMachineModel("Order", "io.casehub.generated.order",
            List.of("IDLE", "PENDING", "APPROVED", "REJECTED"),
            Set.of("APPROVED", "REJECTED"),
            List.of(), List.of());

        var file = StateEnumEmitter.emit(model);

        assertThat(file.fileName()).isEqualTo("OrderState.java");
        assertThat(file.content())
            .contains("package io.casehub.generated.order;")
            .contains("public enum OrderState")
            .contains("IDLE, PENDING, APPROVED, REJECTED");
    }

    @Test
    void emit_convertsKebabCaseToUpperSnakeCase() {
        var model = new StateMachineModel("Flow", "io.casehub.test",
            List.of("waiting-for-input", "processing", "done"),
            Set.of("done"), List.of(), List.of());

        var file = StateEnumEmitter.emit(model);

        assertThat(file.content())
            .contains("WAITING_FOR_INPUT, PROCESSING, DONE");
    }
}
