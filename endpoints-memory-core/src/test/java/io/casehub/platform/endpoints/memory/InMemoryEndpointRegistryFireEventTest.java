package io.casehub.platform.endpoints.memory;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointRegistered;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEndpointRegistryFireEventTest {

    private final List<EndpointRegistered> firedEvents = new ArrayList<>();
    private InMemoryEndpointRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryEndpointRegistry(firedEvents::add);
    }

    private static EndpointDescriptor descriptor(Path path, String tenancyId) {
        return new EndpointDescriptor(
            path, tenancyId, EndpointType.SERVICE, EndpointProtocol.HTTP,
            Map.of(EndpointPropertyKeys.URL, "https://example.com"),
            null, Set.of(EndpointCapability.SEND));
    }

    @Test
    void register_firesEndpointRegisteredWithCorrectDescriptor() {
        var desc = descriptor(Path.of("test", "endpoint"), "tenant-a");

        registry.register(desc);

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).descriptor()).isSameAs(desc);
    }

    @Test
    void register_calledTwice_firesTwoEvents() {
        var desc1 = descriptor(Path.of("first", "endpoint"), "tenant-a");
        var desc2 = descriptor(Path.of("second", "endpoint"), "tenant-a");

        registry.register(desc1);
        registry.register(desc2);

        assertThat(firedEvents).hasSize(2);
    }

    @Test
    void register_upsertSameKey_stillFiresEvent() {
        var path = Path.of("same", "path");
        var original = descriptor(path, "tenant-a");
        var updated = new EndpointDescriptor(
            path, "tenant-a", EndpointType.SYSTEM, EndpointProtocol.GRPC,
            Map.of(EndpointPropertyKeys.URL, "https://updated.com"),
            null, Set.of(EndpointCapability.QUERY));

        registry.register(original);
        registry.register(updated);

        assertThat(firedEvents).hasSize(2);
        assertThat(firedEvents.get(1).descriptor()).isSameAs(updated);
    }
}
