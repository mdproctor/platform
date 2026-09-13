package io.casehub.platform.agent;

import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendInstanceTest {

    @Test
    void holdsInstanceIdAndBackend() {
        AgentBackend backend = stubBackend("openai");
        var instance = new BackendInstance("extra", backend);
        assertThat(instance.instanceId()).isEqualTo("extra");
        assertThat(instance.backend()).isSameAs(backend);
    }

    @Test
    void nullInstanceIdThrows() {
        assertThatThrownBy(() -> new BackendInstance(null, stubBackend("x")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullBackendThrows() {
        assertThatThrownBy(() -> new BackendInstance("id", null))
                .isInstanceOf(NullPointerException.class);
    }

    static AgentBackend stubBackend(String key) {
        return new AgentBackend() {
            @Override public String key() { return key; }
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) {
                return Multi.createFrom().empty();
            }
            @Override public AgentSession openSession(AgentSessionInit i) { return null; }
        };
    }
}
