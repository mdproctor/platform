package io.casehub.yaml.statemachine.generator;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

class StateMachineParserTest {

    @Test
    void parseOrderScenario_extractsStatesEventsTransitions() {
        var file = new File(getClass().getClassLoader()
            .getResource("order.scenario.yaml").getFile());

        var model = StateMachineParser.parse(file, "Order",
            "io.casehub.generated.order");

        assertThat(model.name()).isEqualTo("Order");
        assertThat(model.pkg()).isEqualTo("io.casehub.generated.order");
        assertThat(model.states()).containsExactly(
            "IDLE", "PENDING", "APPROVED", "REJECTED");
        assertThat(model.terminalStates()).containsExactlyInAnyOrder(
            "APPROVED", "REJECTED");
        assertThat(model.events()).hasSize(3);
        assertThat(model.events().stream()
            .filter(e -> e.name().equals("submit")).findFirst().get()
            .fields()).containsEntry("amount", "number");
        assertThat(model.transitions()).hasSize(3);
    }

    @Test
    void parseScenario_withoutEventsSection_producesEmptyFieldMaps() {
        var file = new File(getClass().getClassLoader()
            .getResource("simple.scenario.yaml").getFile());

        var model = StateMachineParser.parse(file, "Simple",
            "io.casehub.generated.simple");

        assertThat(model.events()).allSatisfy(e ->
            assertThat(e.fields()).isEmpty());
    }

    @Test
    void parseScenario_ignoresStepDefinitions() {
        var file = new File(getClass().getClassLoader()
            .getResource("order.scenario.yaml").getFile());

        var model = StateMachineParser.parse(file, "Order",
            "io.casehub.generated.order");

        assertThat(model.events().stream().map(StateMachineModel.EventDef::name))
            .doesNotContain("validate.order");
    }
}
