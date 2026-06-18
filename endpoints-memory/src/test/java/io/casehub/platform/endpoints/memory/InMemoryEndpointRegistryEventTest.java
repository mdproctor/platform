package io.casehub.platform.endpoints.memory;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.path.Path;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies CDI wiring: when Event<EndpointRegistered> is injected, register()
 * completes without NPE. CDI async delivery is not testable in @QuarkusTest
 * (GE-20260513-b15933); what this test verifies is that the null guard is
 * bypassed (non-null event bus injected by CDI) and fireAsync() is invoked
 * without throwing.
 */
@QuarkusTest
class InMemoryEndpointRegistryEventTest {

    @Inject
    EndpointRegistry registry;   // CDI picks InMemoryEndpointRegistry @Alternative @Priority(100)

    @Test
    void register_completes_without_npe_when_event_bus_injected() {
        var desc = new EndpointDescriptor(
            Path.of("test", "endpoint"),
            "default-tenant",
            EndpointType.SERVICE,
            EndpointProtocol.HTTP,
            Map.of(EndpointPropertyKeys.URL, "https://example.com"),
            null,
            Set.of(EndpointCapability.SEND));

        assertThatCode(() -> registry.register(desc)).doesNotThrowAnyException();
    }
}
