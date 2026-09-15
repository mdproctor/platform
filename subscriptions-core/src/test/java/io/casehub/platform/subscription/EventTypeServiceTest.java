package io.casehub.platform.subscription;

import io.casehub.platform.api.subscription.EventTypeDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventTypeServiceTest {

    @Test
    void listEventTypes_delegates_to_registry() {
        var descriptor = new EventTypeDescriptor("test.event", "Test", "desc", List.of());
        var registry = new InMemoryEventTypeRegistry();
        registry.register(descriptor);
        var service = new EventTypeService(registry);

        assertThat(service.listEventTypes()).containsExactly(descriptor);
    }

    @Test
    void listEventTypes_returns_empty_when_registry_empty() {
        var registry = new InMemoryEventTypeRegistry();
        var service = new EventTypeService(registry);

        assertThat(service.listEventTypes()).isEmpty();
    }
}
