package io.casehub.platform.agent.runtime;

import io.casehub.platform.agent.AgentProcess;
import io.casehub.platform.agent.AgentRuntimeConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubprocessRuntimeTest {

    private final SubprocessRuntime runtime = new SubprocessRuntime();

    @Test
    void spawnEchoProcess() throws IOException {
        var config = new AgentRuntimeConfig("echo", List.of("hello"), Map.of(), null);
        try (AgentProcess process = runtime.spawn(config)) {
            String output = new String(process.stdout().readAllBytes()).trim();
            assertThat(output).isEqualTo("hello");
            int exitCode = process.exitCode().join();
            assertThat(exitCode).isZero();
        }
    }

    @Test
    void destroyTerminatesProcess() throws IOException {
        var config = new AgentRuntimeConfig("sleep", List.of("60"), Map.of(), null);
        AgentProcess process = runtime.spawn(config);
        process.destroy();
        int exitCode = process.exitCode().join();
        assertThat(exitCode).isNotZero();
    }

    @Test
    void destroyForciblyTerminatesProcess() throws IOException {
        var config = new AgentRuntimeConfig("sleep", List.of("60"), Map.of(), null);
        AgentProcess process = runtime.spawn(config);
        process.destroyForcibly();
        int exitCode = process.exitCode().join();
        assertThat(exitCode).isNotZero();
    }

    @Test
    void envPassedToProcess() throws IOException {
        var config = new AgentRuntimeConfig("sh", List.of("-c", "echo $TEST_VAR"),
                Map.of("TEST_VAR", "hello_runtime"), null);
        try (AgentProcess process = runtime.spawn(config)) {
            String output = new String(process.stdout().readAllBytes()).trim();
            assertThat(output).isEqualTo("hello_runtime");
        }
    }

    @Test
    void stdinWritesToProcess() throws IOException {
        var config = new AgentRuntimeConfig("cat", List.of(), Map.of(), null);
        try (AgentProcess process = runtime.spawn(config)) {
            process.stdin().write("hello from stdin".getBytes());
            process.stdin().close();
            String output = new String(process.stdout().readAllBytes()).trim();
            assertThat(output).isEqualTo("hello from stdin");
        }
    }

    @Test
    void stderrCaptured() throws IOException {
        var config = new AgentRuntimeConfig("sh", List.of("-c", "echo err >&2"),
                Map.of(), null);
        try (AgentProcess process = runtime.spawn(config)) {
            process.exitCode().join();
            String stderr = new String(process.stderr().readAllBytes()).trim();
            assertThat(stderr).isEqualTo("err");
        }
    }
}
