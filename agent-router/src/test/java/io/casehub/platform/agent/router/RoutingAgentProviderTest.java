package io.casehub.platform.agent.router;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelTier;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingAgentProviderTest {

    static AgentBackend stubBackend(String key) {
        return new AgentBackend() {
            @Override
            public String key() { return key; }

            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                return Multi.createFrom().item(new AgentEvent.TextDelta("from-" + key));
            }

            @Override
            public AgentSession openSession(AgentSessionInit init) { return null; }
        };
    }

    static AgentBackend capturingBackend(String key, AtomicReference<AgentSessionConfig> configCapture,
                                         AtomicReference<AgentSessionInit> initCapture) {
        return new AgentBackend() {
            @Override
            public String key() { return key; }

            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                configCapture.set(config);
                return Multi.createFrom().item(new AgentEvent.TextDelta("from-" + key));
            }

            @Override
            public AgentSession openSession(AgentSessionInit init) {
                initCapture.set(init);
                return null;
            }
        };
    }

    static InMemoryBackendInstanceRegistry backendRegistry(AgentBackend... backends) {
        var registry = new InMemoryBackendInstanceRegistry();
        for (var backend : backends) {
            registry.register(backend);
        }
        return registry;
    }


    static ModelRegistry emptyRegistry() {
        return new ModelRegistry() {
            @Override
            public Optional<ModelDescriptor> resolveById(String id) { return Optional.empty(); }

            @Override
            public List<ModelDescriptor> query(ModelQuery query) { return List.of(); }

            @Override
            public List<ModelDescriptor> all() { return List.of(); }
        };
    }

    static ModelRegistry registryWith(ModelDescriptor... descriptors) {
        Map<String, ModelDescriptor> map = new HashMap<>();
        for (var d : descriptors) map.put(d.id(), d);
        return new ModelRegistry() {
            @Override
            public Optional<ModelDescriptor> resolveById(String id) {
                return Optional.ofNullable(map.get(id));
            }

            @Override
            public List<ModelDescriptor> query(ModelQuery query) { return List.copyOf(map.values()); }

            @Override
            public List<ModelDescriptor> all() { return List.copyOf(map.values()); }
        };
    }

    static ModelDescriptor descriptor(String id, String backendKey) {
        return new ModelDescriptor(id, id, backendKey, null, "test-vendor", "test-family",
                "Test " + id, ModelTier.STANDARD, Set.of(), 128000, 16384,
                ModelLocality.CLOUD, null, null, Map.of());
    }

    static ModelDescriptor descriptorWithInstance(String id, String backendKey, String instanceId) {
        return new ModelDescriptor(id, id, backendKey, instanceId, "test-vendor", "test-family",
                                   "Test " + id, ModelTier.STANDARD, Set.of(), 128000, 16384,
                                   ModelLocality.CLOUD, null, null, Map.of());
    }


    // --- Existing behavior (key-based routing) ---

    @Test
    void routesByModelKey() {
        var router = new RoutingAgentProvider(
                backendRegistry(stubBackend("claude"), stubBackend("openai")), "claude", emptyRegistry());
        var config = AgentSessionConfig.of("sys", "user", "openai");
        var events = router.invoke(config).collect().asList().await().indefinitely();
        assertThat(events).hasSize(1);
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("from-openai");
    }

    @Test
    void nullModelUsesDefault() {
        var router = new RoutingAgentProvider(
                backendRegistry(stubBackend("claude"), stubBackend("openai")), "claude", emptyRegistry());
        var config = AgentSessionConfig.of("sys", "user");
        var events = router.invoke(config).collect().asList().await().indefinitely();
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("from-claude");
    }

    @Test
    void unknownKeyWithNoRegistryMatchThrows() {
        var router = new RoutingAgentProvider(
                backendRegistry(stubBackend("claude")), "claude", emptyRegistry());
        var config = AgentSessionConfig.of("sys", "user", "mistral");
        assertThatThrownBy(() -> router.invoke(config))
                  .isInstanceOf(IllegalArgumentException.class)
                  .hasMessageContaining("mistral");
    }

    @Test
    void noDefaultBackendThrowsOnNullModel() {
        var router = new RoutingAgentProvider(
                backendRegistry(stubBackend("openai")), "claude", emptyRegistry());
        var config = AgentSessionConfig.of("sys", "user");
        assertThatThrownBy(() -> router.invoke(config))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("No default backend");
    }

    @Test
    void openSessionRoutesToCorrectBackend() {
        var router = new RoutingAgentProvider(
                backendRegistry(stubBackend("claude"), stubBackend("openai")), "claude", emptyRegistry());
        var init = AgentSessionInit.of("sys", "openai");
        assertThat(router.openSession(init)).isNull();
    }

    @Test
    void emptyBackendsThrowsOnAnyCall() {
        var router = new RoutingAgentProvider(backendRegistry(), "claude", emptyRegistry());
        var config = AgentSessionConfig.of("sys", "user");
        assertThatThrownBy(() -> router.invoke(config))
                  .isInstanceOf(IllegalStateException.class);
    }

    // --- Registry-path resolution ---

    @Test
    void registryPathResolvesModelToBackend() {
        var registry = registryWith(descriptor("claude-sonnet-5", "claude"));
        var router = new RoutingAgentProvider(
                backendRegistry(stubBackend("claude"), stubBackend("openai")), "claude", registry);
        var config = AgentSessionConfig.of("sys", "user", "claude-sonnet-5");
        var events = router.invoke(config).collect().asList().await().indefinitely();
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("from-claude");
    }

    @Test
    void registryPathRewritesModelInConfig() {
        var configCapture = new AtomicReference<AgentSessionConfig>();
        var initCapture = new AtomicReference<AgentSessionInit>();
        var registry = registryWith(descriptor("claude-sonnet-5", "claude"));
        var router = new RoutingAgentProvider(
                backendRegistry(capturingBackend("claude", configCapture, initCapture)), "claude", registry);
        var config = AgentSessionConfig.of("sys", "user", "claude-sonnet-5");
        router.invoke(config).collect().asList().await().indefinitely();
        assertThat(configCapture.get().model()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void registryPathRewritesModelInSessionInit() {
        var configCapture = new AtomicReference<AgentSessionConfig>();
        var initCapture = new AtomicReference<AgentSessionInit>();
        var registry = registryWith(descriptor("gpt-4.1", "openai"));
        var router = new RoutingAgentProvider(
                backendRegistry(capturingBackend("openai", configCapture, initCapture)), "openai", registry);
        var init = AgentSessionInit.of("sys", "gpt-4.1");
        router.openSession(init);
        assertThat(initCapture.get().model()).isEqualTo("gpt-4.1");
    }

    @Test
    void keyBasedPathNullsModelInConfig() {
        var configCapture = new AtomicReference<AgentSessionConfig>();
        var initCapture = new AtomicReference<AgentSessionInit>();
        var router = new RoutingAgentProvider(
                backendRegistry(capturingBackend("claude", configCapture, initCapture)), "claude", emptyRegistry());
        var config = AgentSessionConfig.of("sys", "user", "claude");
        router.invoke(config).collect().asList().await().indefinitely();
        assertThat(configCapture.get().model()).isNull();
    }

    @Test
    void registryPathMissingBackendThrows() {
        var registry = registryWith(descriptor("gemini-2.5-pro", "gemini"));
        var router = new RoutingAgentProvider(
                backendRegistry(stubBackend("claude")), "claude", registry);
        var config = AgentSessionConfig.of("sys", "user", "gemini-2.5-pro");
        assertThatThrownBy(() -> router.invoke(config))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("gemini")
                  .hasMessageContaining("no backend with that key/instance is registered");
    }

    @Test
    void registryTakesPriorityOverKeyMatch() {
        var configCapture = new AtomicReference<AgentSessionConfig>();
        var initCapture = new AtomicReference<AgentSessionInit>();
        var registry = registryWith(descriptor("claude", "claude"));
        var router = new RoutingAgentProvider(
                backendRegistry(capturingBackend("claude", configCapture, initCapture)), "claude", registry);
        var config = AgentSessionConfig.of("sys", "user", "claude");
        router.invoke(config).collect().asList().await().indefinitely();
        // Registry path sets the model ID; key-based path would null it
        assertThat(configCapture.get().model()).isEqualTo("claude");
    }

    @Test
    void resolvesByBackendInstanceId() {
        var vertexBackend = stubBackend("claude");
        var reg           = new InMemoryBackendInstanceRegistry();
        reg.register(new InstanceWrapper("claude", "default", stubBackend("claude")));
        reg.register(new InstanceWrapper("claude", "vertex", vertexBackend));
        var modelReg = registryWith(descriptorWithInstance("claude-vertex-model", "claude", "vertex"));
        var router   = new RoutingAgentProvider(reg, "claude", modelReg);
        var config   = AgentSessionConfig.of("sys", "user", "claude-vertex-model");
        var events   = router.invoke(config).collect().asList().await().indefinitely();
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("from-claude");
    }

    @Test
    void nullInstanceIdFallsBackToDefault() {
        var reg      = backendRegistry(stubBackend("claude"));
        var modelReg = registryWith(descriptor("claude-sonnet-5", "claude"));
        var router   = new RoutingAgentProvider(reg, "claude", modelReg);
        var config   = AgentSessionConfig.of("sys", "user", "claude-sonnet-5");
        var events   = router.invoke(config).collect().asList().await().indefinitely();
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("from-claude");
    }

    @Test
    void missingInstanceIdThrows() {
        var reg      = backendRegistry(stubBackend("claude"));
        var modelReg = registryWith(descriptorWithInstance("claude-vertex-model", "claude", "vertex"));
        var router   = new RoutingAgentProvider(reg, "claude", modelReg);
        var config   = AgentSessionConfig.of("sys", "user", "claude-vertex-model");
        assertThatThrownBy(() -> router.invoke(config))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("claude")
                  .hasMessageContaining("vertex");
    }
}
