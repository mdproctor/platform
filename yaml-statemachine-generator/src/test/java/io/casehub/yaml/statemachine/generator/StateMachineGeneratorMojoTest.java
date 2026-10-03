package io.casehub.yaml.statemachine.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StateMachineGeneratorMojoTest {

    @TempDir Path tempDir;

    @Test
    void generate_producesAllThreeFiles() throws Exception {
        var yamlFile = new File(getClass().getClassLoader()
            .getResource("order.scenario.yaml").getFile());
        var outputDir = tempDir.resolve("generated");

        StateMachineGeneratorMojo.generateStateMachine(
            yamlFile, "Order", "io.casehub.generated.order",
            outputDir.toFile());

        var pkg = outputDir.resolve("io/casehub/generated/order");
        assertThat(pkg.resolve("OrderState.java")).exists();
        assertThat(pkg.resolve("OrderEvent.java")).exists();
        assertThat(pkg.resolve("OrderDispatch.java")).exists();

        var stateContent = Files.readString(pkg.resolve("OrderState.java"));
        assertThat(stateContent).contains("public enum OrderState");
    }

    @Test
    void generate_writesMetaInfDescriptor() throws Exception {
        var resourceDir = tempDir.resolve("resources");

        StateMachineGeneratorMojo.writeDescriptor(
            "Order", "io.casehub.generated.order",
            resourceDir.toFile());

        var descriptor = resourceDir.resolve(
            "META-INF/yaml-dispatch/order.properties");
        assertThat(descriptor).exists();
        var content = Files.readString(descriptor);
        assertThat(content)
            .contains("dispatch-class=io.casehub.generated.order.OrderDispatch")
            .contains("state-enum=io.casehub.generated.order.OrderState")
            .contains("event-type=io.casehub.generated.order.OrderEvent");
    }
}
