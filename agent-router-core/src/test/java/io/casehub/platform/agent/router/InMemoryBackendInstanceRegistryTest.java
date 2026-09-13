package io.casehub.platform.agent.router;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryBackendInstanceRegistryTest {

    @Test
    void registerAndResolve() {
        var registry = new InMemoryBackendInstanceRegistry();
        var backend = stubBackend("claude", "default");
        registry.register(backend);
        assertThat(registry.resolve("claude", "default")).contains(backend);
    }

    @Test
    void resolveUnknownReturnsEmpty() {
        var registry = new InMemoryBackendInstanceRegistry();
        assertThat(registry.resolve("claude", "default")).isEmpty();
    }

    @Test
    void resolveByKeyReturnsAllInstances() {
        var registry = new InMemoryBackendInstanceRegistry();
        var direct = stubBackend("claude", "default");
        var vertex = stubBackend("claude", "vertex");
        var openai = stubBackend("openai", "default");
        registry.register(direct);
        registry.register(vertex);
        registry.register(openai);
        assertThat(registry.resolveByKey("claude")).containsExactlyInAnyOrder(direct, vertex);
    }

    @Test
    void lastWriteWinsOnSameCompoundKey() {
        var registry = new InMemoryBackendInstanceRegistry();
        var first = stubBackend("openai", "default");
        var second = stubBackend("openai", "default");
        registry.register(first);
        registry.register(second);
        assertThat(registry.resolve("openai", "default")).contains(second);
    }

    @Test
    void instanceWrapperDelegatesInvoke() {
        var delegate = stubBackend("openai", "default");
        var wrapper = new InstanceWrapper("openai", "extra", delegate);
        assertThat(wrapper.key()).isEqualTo("openai");
        assertThat(wrapper.instanceId()).isEqualTo("extra");
        var events = wrapper.invoke(AgentSessionConfig.of("s", "u"))
                .collect().asList().await().indefinitely();
        assertThat(events).hasSize(1);
    }

    @Test
    void resolveByKeyReturnsEmptyForUnknown() {
        var registry = new InMemoryBackendInstanceRegistry();
        assertThat(registry.resolveByKey("unknown")).isEmpty();
    }

    static AgentBackend stubBackend(String key, String instanceId) {
        return new AgentBackend() {
            @Override public String key() { return key; }
            @Override public String instanceId() { return instanceId; }
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) {
                return Multi.createFrom().item(new AgentEvent.TextDelta("from-" + key + "-" + instanceId));
            }
            @Override public AgentSession openSession(AgentSessionInit i) { return null; }
        };
    }
}
