package io.casehub.platform.simulation.platform;

import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.simulation.DataRealism;
import io.casehub.platform.simulation.InvocationRecord;
import io.casehub.platform.simulation.MapSimulationConfig;
import io.casehub.platform.simulation.SimulationRuntime;
import io.casehub.platform.simulation.SimulationVerifier;
import io.casehub.platform.simulation.generated.EndpointRegistryQN;
import io.casehub.platform.simulation.generated.SimulatedEndpointRegistry;
import io.casehub.platform.simulation.inmem.InMemorySimulationCorpus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DataRealismEndToEndTest {

    private SimulationRuntime runtime;
    private SimulatedEndpointRegistry decorator;

    @BeforeEach
    void setUp() throws Exception {
        final var config = MapSimulationConfig.builder().build();
        runtime = new SimulationRuntime(config, new InMemorySimulationCorpus<>());

        final CurrentPrincipal principal = new CurrentPrincipal() {
            @Override public String actorId() { return "test-actor"; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId() { return "test-tenant"; }
            @Override public boolean isCrossTenantAdmin() { return false; }
        };

        final EndpointRegistry noOpDelegate = new EndpointRegistry() {
            @Override public void register(io.casehub.platform.api.endpoints.EndpointDescriptor endpoint) {}
            @Override public java.util.Optional<io.casehub.platform.api.endpoints.EndpointDescriptor> resolve(Path path, String tenancyId) { return java.util.Optional.empty(); }
            @Override public java.util.List<io.casehub.platform.api.endpoints.EndpointDescriptor> discover(EndpointQuery query) { return java.util.List.of(); }
            @Override public void deregister(Path path, String tenancyId) {}
        };

        decorator = new SimulatedEndpointRegistry();
        inject(decorator, "delegate", noOpDelegate);
        inject(decorator, "simulation", runtime);
        inject(decorator, "currentPrincipal", principal);
    }

    @Test
    void strategyResolvedPath() {
        final var overlayCorpus = new InMemorySimulationCorpus<>();
        overlayCorpus.seed(EndpointRegistryQN.DISCOVER, List.of(
                new InvocationRecord<>("test-tenant", null, null, List.of(), Instant.now())));

        final var overlay = runtime.pushOverlay(
                MapSimulationConfig.builder()
                        .strategy(EndpointRegistryQN.DISCOVER, "sequential")
                        .build(),
                overlayCorpus);

        decorator.discover(new EndpointQuery("test-tenant", null, null, Set.of()));

        final var journal = runtime.journal(overlay);
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0).dataRealism()).isEqualTo(DataRealism.STRUCTURALLY_VALID);

        final var verifier = SimulationVerifier.on(overlay);
        verifier.method(EndpointRegistryQN.DISCOVER).allSimulated();
    }

    @Test
    void interpretiveFallthroughPath() {
        final var overlay = runtime.pushOverlay(
                MapSimulationConfig.builder()
                        .fallthroughRealism(EndpointRegistryQN.RESOLVE,
                                DataRealism.DOMAIN_PLAUSIBLE)
                        .build());

        decorator.resolve(Path.of("test"), "test-tenant");

        final var journal = runtime.journal(overlay);
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0).dataRealism())
                .isEqualTo(DataRealism.DOMAIN_PLAUSIBLE);
    }

    @Test
    void deterministicDelegatePath() {
        final var overlay = runtime.pushOverlay(
                MapSimulationConfig.builder().build());

        decorator.register(null);

        final var journal = runtime.journal(overlay);
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0).dataRealism()).isNull();

        final var verifier = SimulationVerifier.on(overlay);
        verifier.method(EndpointRegistryQN.REGISTER).noneSimulated();
    }

    @Test
    void strategyCannotResolveFallsThrough() {
        final var overlay = runtime.pushOverlay(
                MapSimulationConfig.builder()
                        .strategy(EndpointRegistryQN.DEREGISTER, "sequential")
                        .fallthroughRealism(EndpointRegistryQN.DEREGISTER,
                                DataRealism.DOMAIN_PLAUSIBLE)
                        .build());

        decorator.deregister(Path.of("nonexistent"), "test-tenant");

        final var journal = runtime.journal(overlay);
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0).dataRealism())
                .isEqualTo(DataRealism.DOMAIN_PLAUSIBLE);
    }

    private static void inject(final Object target, final String fieldName,
                                final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
