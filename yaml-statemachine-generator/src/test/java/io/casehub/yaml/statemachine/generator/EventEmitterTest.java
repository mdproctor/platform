package io.casehub.yaml.statemachine.generator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventEmitterTest {

    @Test
    void emit_generatesSealedInterfaceWithRecords() {
        var model = new StateMachineModel("Order", "io.casehub.generated.order",
            List.of("IDLE", "PENDING", "APPROVED"),
            Set.of("APPROVED"),
            List.of(
                new StateMachineModel.EventDef("submit",
                    Map.of("amount", "number", "customer", "string")),
                new StateMachineModel.EventDef("approve",
                    Map.of("count", "integer"))
            ),
            List.of());

        var file = EventEmitter.emit(model);

        assertThat(file.fileName()).isEqualTo("OrderEvent.java");
        assertThat(file.content())
            .contains("public sealed interface OrderEvent")
            .contains("record Submit(")
            .contains("record Approve(int count)")
            .contains("implements OrderEvent");
    }

    @Test
    void emit_emptyFieldsProduceEmptyRecord() {
        var model = new StateMachineModel("Simple", "io.casehub.test",
            List.of("OPEN", "CLOSED"), Set.of("CLOSED"),
            List.of(new StateMachineModel.EventDef("close", Map.of())),
            List.of());

        var file = EventEmitter.emit(model);

        assertThat(file.content()).contains("record Close() implements SimpleEvent");
    }

    @Test
    void emit_typeMapping() {
        var model = new StateMachineModel("Test", "io.casehub.test",
            List.of("A", "B"), Set.of("B"),
            List.of(new StateMachineModel.EventDef("check",
                Map.of("flag", "boolean", "count", "integer",
                       "rate", "number", "label", "string"))),
            List.of());

        var file = EventEmitter.emit(model);

        assertThat(file.content())
            .contains("boolean flag")
            .contains("int count")
            .contains("double rate")
            .contains("String label");
    }
}
