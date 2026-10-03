package io.casehub.yaml.statemachine.generator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchEmitterTest {

    @Test
    void emit_generatesTypedDispatchClass() {
        var model = new StateMachineModel("Order", "io.casehub.generated.order",
            List.of("IDLE", "PENDING", "APPROVED", "REJECTED"),
            Set.of("APPROVED", "REJECTED"),
            List.of(
                new StateMachineModel.EventDef("submit",
                    Map.of("amount", "number")),
                new StateMachineModel.EventDef("approve",
                    Map.of("count", "integer")),
                new StateMachineModel.EventDef("reject", Map.of())
            ),
            List.of(
                new StateMachineModel.TransitionDef(
                    "IDLE", "submit", "PENDING", "amount > 0"),
                new StateMachineModel.TransitionDef(
                    "PENDING", "approve", "APPROVED", "count >= 2"),
                new StateMachineModel.TransitionDef(
                    "PENDING", "reject", "REJECTED", null)
            ));

        var file = DispatchEmitter.emit(model);

        assertThat(file.fileName()).isEqualTo("OrderDispatch.java");
        assertThat(file.content())
            .contains("public final class OrderDispatch")
            .contains("OrcStateMachine<OrderState> sm")
            .contains("public boolean fire(OrderEvent event)")
            .contains("case OrderEvent.Submit s")
            .contains("sm.transition(OrderState.IDLE, OrderState.PENDING, s)")
            .contains("case OrderEvent.Reject r")
            .contains("sm.currentState() == OrderState.PENDING")
            .contains("static OrderDispatch create()")
            .contains("targeting(OrcStateMachine<OrderState>");
    }

    @Test
    void emit_guardedTransitionHasWhenClause() {
        var model = new StateMachineModel("Order", "io.casehub.generated.order",
            List.of("IDLE", "PENDING"), Set.of(),
            List.of(new StateMachineModel.EventDef("submit",
                Map.of("amount", "number"))),
            List.of(new StateMachineModel.TransitionDef(
                "IDLE", "submit", "PENDING", "amount > 0")));

        var file = DispatchEmitter.emit(model);

        assertThat(file.content()).contains("s.amount() > 0");
    }

    @Test
    void emit_unguardedTransitionHasStateCheckOnly() {
        var model = new StateMachineModel("Simple", "io.casehub.test",
            List.of("OPEN", "CLOSED"), Set.of("CLOSED"),
            List.of(new StateMachineModel.EventDef("close", Map.of())),
            List.of(new StateMachineModel.TransitionDef(
                "OPEN", "close", "CLOSED", null)));

        var file = DispatchEmitter.emit(model);

        assertThat(file.content())
            .contains("sm.currentState() == SimpleState.OPEN");
    }
}
