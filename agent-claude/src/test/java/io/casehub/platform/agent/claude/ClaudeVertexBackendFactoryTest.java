package io.casehub.platform.agent.claude;

import io.casehub.platform.agent.BackendInstance;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ClaudeVertexBackendFactoryTest {

    private final ClaudeVertexBackendFactory factory = new ClaudeVertexBackendFactory();

    @Test
    void backendKey_isClaude() {
        assertThat(factory.backendKey()).isEqualTo("claude");
    }

    @Test
    void handles_vertexRefWithProjectId() {
        assertThat(factory.handles("cloud-vertex",
            Map.of("project-id", "my-proj", "region", "us-east1"))).isTrue();
    }

    @Test
    void handles_rejectsNonVertexRef() {
        assertThat(factory.handles("cloud-anthropic",
            Map.of("api-key", "sk-xxx"))).isFalse();
    }

    @Test
    void handles_rejectsMissingProjectId() {
        assertThat(factory.handles("cloud-vertex",
            Map.of("region", "us-east1"))).isFalse();
    }

    @Test
    void create_returnsBackendInstance() {
        BackendInstance instance = factory.create("cloud-vertex",
            Map.of("project-id", "my-proj", "region", "us-east1"));
        assertThat(instance.instanceId()).isEqualTo("vertex");
        assertThat(instance.backend()).isInstanceOf(ClaudeAgentProvider.class);
        assertThat(instance.backend().key()).isEqualTo("claude");
    }

    @Test
    void create_defaultsRegionToUsCentral1() {
        BackendInstance instance = factory.create("cloud-vertex",
            Map.of("project-id", "my-proj"));
        assertThat(instance).isNotNull();
    }

    @Test
    void deriveInstanceId_cloudVertex() {
        assertThat(factory.deriveInstanceId("cloud-vertex")).isEqualTo("vertex");
    }

    @Test
    void deriveInstanceId_customRef() {
        assertThat(factory.deriveInstanceId("cloud-staging-vertex")).isEqualTo("staging-vertex");
    }
}
