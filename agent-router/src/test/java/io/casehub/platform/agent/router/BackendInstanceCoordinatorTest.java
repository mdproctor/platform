package io.casehub.platform.agent.router;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.BackendInstance;
import io.casehub.platform.agent.BackendInstanceFactory;
import io.casehub.platform.api.credentials.LlmCredentialStore;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BackendInstanceCoordinatorTest {

    @Test
    void registersCdiBackends() {
        var registry = new InMemoryBackendInstanceRegistry();
        var backend = stubBackend("claude", "default");
        var coordinator = new BackendInstanceCoordinator(
                registry, List.of(backend), List.of(), emptyCredentialStore());
        coordinator.onStartup();
        assertThat(registry.resolve("claude", "default")).contains(backend);
    }

    @Test
    void registersMultipleCdiBackends() {
        var registry = new InMemoryBackendInstanceRegistry();
        var claude = stubBackend("claude", "default");
        var openai = stubBackend("openai", "default");
        var coordinator = new BackendInstanceCoordinator(
                registry, List.of(claude, openai), List.of(), emptyCredentialStore());
        coordinator.onStartup();
        assertThat(registry.resolve("claude", "default")).contains(claude);
        assertThat(registry.resolve("openai", "default")).contains(openai);
    }

    @Test
    void createsFactoryInstancesFromCredentials() {
        var registry = new InMemoryBackendInstanceRegistry();
        var extraBackend = stubBackend("openai", "extra");
        var factory = new BackendInstanceFactory() {
            @Override public String backendKey() { return "openai"; }
            @Override public boolean handles(String ref, Map<String, String> creds) {
                return ref.contains("openai") && creds.containsKey("api-key");
            }
            @Override public BackendInstance create(String ref, Map<String, String> creds) {
                return new BackendInstance("extra", extraBackend);
            }
        };
        var store = stubCredentialStore(Map.of("extra-openai", Map.of("api-key", "sk-extra")));
        var coordinator = new BackendInstanceCoordinator(
                registry, List.of(), List.of(factory), store);
        coordinator.onStartup();
        assertThat(registry.resolve("openai", "extra")).isPresent();
    }

    @Test
    void factorySkipsNonMatchingCredentials() {
        var registry = new InMemoryBackendInstanceRegistry();
        var factory = new BackendInstanceFactory() {
            @Override public String backendKey() { return "openai"; }
            @Override public boolean handles(String ref, Map<String, String> creds) {
                return ref.contains("openai");
            }
            @Override public BackendInstance create(String ref, Map<String, String> creds) {
                return new BackendInstance("x", stubBackend("openai", "x"));
            }
        };
        var store = stubCredentialStore(Map.of("cloud-anthropic", Map.of("api-key", "sk-ant")));
        var coordinator = new BackendInstanceCoordinator(
                registry, List.of(), List.of(factory), store);
        coordinator.onStartup();
        assertThat(registry.resolve("openai", "x")).isEmpty();
    }

    @Test
    void factorySkipsEmptyCredentials() {
        var registry = new InMemoryBackendInstanceRegistry();
        var factory = new BackendInstanceFactory() {
            @Override public String backendKey() { return "openai"; }
            @Override public boolean handles(String ref, Map<String, String> creds) {
                return true;
            }
            @Override public BackendInstance create(String ref, Map<String, String> creds) {
                return new BackendInstance("x", stubBackend("openai", "x"));
            }
        };
        var store = new LlmCredentialStore() {
            @Override public void store(String t, String r, Map<String, String> c) {}
            @Override public Map<String, String> resolve(String t, String r) { return Map.of(); }
            @Override public void delete(String t, String r) {}
            @Override public List<String> listRefs(String t) { return List.of("empty-ref"); }
        };
        var coordinator = new BackendInstanceCoordinator(
                registry, List.of(), List.of(factory), store);
        coordinator.onStartup();
        assertThat(registry.resolve("openai", "x")).isEmpty();
    }

    @Test
    void cdiAndFactoryBackendsCoexist() {
        var registry = new InMemoryBackendInstanceRegistry();
        var cdiBackend = stubBackend("openai", "default");
        var factoryBackend = stubBackend("openai", "extra");
        var factory = new BackendInstanceFactory() {
            @Override public String backendKey() { return "openai"; }
            @Override public boolean handles(String ref, Map<String, String> creds) { return true; }
            @Override public BackendInstance create(String ref, Map<String, String> creds) {
                return new BackendInstance("extra", factoryBackend);
            }
        };
        var store = stubCredentialStore(Map.of("extra-openai", Map.of("api-key", "sk")));
        var coordinator = new BackendInstanceCoordinator(
                registry, List.of(cdiBackend), List.of(factory), store);
        coordinator.onStartup();
        assertThat(registry.resolve("openai", "default")).contains(cdiBackend);
        assertThat(registry.resolve("openai", "extra")).isPresent();
        assertThat(registry.resolveByKey("openai")).hasSize(2);
    }

    static AgentBackend stubBackend(String key, String instanceId) {
        return new AgentBackend() {
            @Override public String key() { return key; }
            @Override public String instanceId() { return instanceId; }
            @Override public Multi<AgentEvent> invoke(AgentSessionConfig c) {
                return Multi.createFrom().item(new AgentEvent.TextDelta("from-" + key));
            }
            @Override public AgentSession openSession(AgentSessionInit i) { return null; }
        };
    }

    static LlmCredentialStore emptyCredentialStore() {
        return stubCredentialStore(Map.of());
    }

    static LlmCredentialStore stubCredentialStore(Map<String, Map<String, String>> entries) {
        return new LlmCredentialStore() {
            @Override public void store(String t, String r, Map<String, String> c) {}
            @Override public Map<String, String> resolve(String t, String r) {
                return entries.getOrDefault(r, Map.of());
            }
            @Override public void delete(String t, String r) {}
            @Override public List<String> listRefs(String t) {
                return List.copyOf(entries.keySet());
            }
        };
    }
}
