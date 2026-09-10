package io.casehub.platform.agent.router;

import io.casehub.platform.agent.AgentBackend;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void routesByModelKey() {
        var router = new RoutingAgentProvider(
                List.of(stubBackend("claude"), stubBackend("openai")), "claude");
        var config = AgentSessionConfig.of("sys", "user", "openai");
        var events = router.invoke(config).collect().asList().await().indefinitely();
        assertThat(events).hasSize(1);
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("from-openai");
    }

    @Test
    void nullModelUsesDefault() {
        var router = new RoutingAgentProvider(
                List.of(stubBackend("claude"), stubBackend("openai")), "claude");
        var config = AgentSessionConfig.of("sys", "user");
        var events = router.invoke(config).collect().asList().await().indefinitely();
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("from-claude");
    }

    @Test
    void unknownKeyWithCatchAllFallsThrough() {
        var router = new RoutingAgentProvider(
                List.of(stubBackend("claude"), stubBackend("langchain4j")), "claude");
        var config = AgentSessionConfig.of("sys", "user", "mistral");
        var events = router.invoke(config).collect().asList().await().indefinitely();
        assertThat(((AgentEvent.TextDelta) events.get(0)).text()).isEqualTo("from-langchain4j");
    }

    @Test
    void unknownKeyWithNoCatchAllThrows() {
        var router = new RoutingAgentProvider(
                List.of(stubBackend("claude")), "claude");
        var config = AgentSessionConfig.of("sys", "user", "mistral");
        assertThatThrownBy(() -> router.invoke(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mistral");
    }

    @Test
    void noDefaultBackendThrowsOnNullModel() {
        var router = new RoutingAgentProvider(
                List.of(stubBackend("openai")), "claude");
        var config = AgentSessionConfig.of("sys", "user");
        assertThatThrownBy(() -> router.invoke(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No default backend");
    }

    @Test
    void openSessionRoutesToCorrectBackend() {
        var router = new RoutingAgentProvider(
                List.of(stubBackend("claude"), stubBackend("openai")), "claude");
        var init = AgentSessionInit.of("sys", "openai");
        // stubBackend returns null for openSession — just verifying no exception on routing
        assertThat(router.openSession(init)).isNull();
    }

    @Test
    void emptyBackendsThrowsOnAnyCall() {
        var router = new RoutingAgentProvider(List.of(), "claude");
        var config = AgentSessionConfig.of("sys", "user");
        assertThatThrownBy(() -> router.invoke(config))
                .isInstanceOf(IllegalStateException.class);
    }
}
