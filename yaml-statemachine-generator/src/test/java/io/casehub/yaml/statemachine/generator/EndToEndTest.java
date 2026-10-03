package io.casehub.yaml.statemachine.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndTest {

    @TempDir Path tempDir;

    @Test
    void fullGeneration_producesCompilableSource() throws Exception {
        var yamlFile = new File(getClass().getClassLoader()
            .getResource("approval-workflow.scenario.yaml").getFile());
        var outputDir = tempDir.resolve("src");

        StateMachineGeneratorMojo.generateStateMachine(
            yamlFile, "ApprovalWorkflow",
            "io.casehub.generated.approval", outputDir.toFile());

        var pkg = outputDir.resolve("io/casehub/generated/approval");

        var stateSource = Files.readString(
            pkg.resolve("ApprovalWorkflowState.java"));
        assertThat(stateSource)
            .contains("DRAFT, REVIEW, APPROVED, REJECTED")
            .contains("package io.casehub.generated.approval;");

        var eventSource = Files.readString(
            pkg.resolve("ApprovalWorkflowEvent.java"));
        assertThat(eventSource)
            .contains("sealed interface ApprovalWorkflowEvent")
            .contains("record Submit(double amount)")
            .contains("record Approve(int count)")
            .contains("record Reject(String reason)");

        var dispatchSource = Files.readString(
            pkg.resolve("ApprovalWorkflowDispatch.java"));
        assertThat(dispatchSource)
            .contains("public boolean fire(ApprovalWorkflowEvent event)")
            .contains("case ApprovalWorkflowEvent.Submit")
            .contains("case ApprovalWorkflowEvent.Approve")
            .contains("case ApprovalWorkflowEvent.Reject")
            .contains("sm.currentState() == ApprovalWorkflowState.REVIEW")
            .contains("static ApprovalWorkflowDispatch create()");
    }

    @Test
    void fullGeneration_metaInfDescriptor() throws Exception {
        var resourceDir = tempDir.resolve("resources");

        StateMachineGeneratorMojo.writeDescriptor(
            "ApprovalWorkflow", "io.casehub.generated.approval",
            resourceDir.toFile());

        var descriptor = resourceDir.resolve(
            "META-INF/yaml-dispatch/approvalworkflow.properties");
        assertThat(descriptor).exists();
        var props = Files.readString(descriptor);
        assertThat(props).contains(
            "dispatch-class=io.casehub.generated.approval.ApprovalWorkflowDispatch");
    }

    @Test
    void fullGeneration_noEventsSection_producesEmptyRecords()
            throws Exception {
        var yamlFile = new File(getClass().getClassLoader()
            .getResource("simple.scenario.yaml").getFile());
        var outputDir = tempDir.resolve("simple-src");

        StateMachineGeneratorMojo.generateStateMachine(
            yamlFile, "Simple", "io.casehub.generated.simple",
            outputDir.toFile());

        var pkg = outputDir.resolve("io/casehub/generated/simple");
        var eventSource = Files.readString(
            pkg.resolve("SimpleEvent.java"));
        assertThat(eventSource).contains("record Close()");
    }
}
