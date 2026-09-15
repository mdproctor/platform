package io.casehub.platform.spring.rest;

import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.subscription.EventTypeService;
import io.casehub.platform.subscription.InMemoryEventTypeRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventTypeRestControllerTest {

    @Test
    void listEventTypes_delegates_to_service() {
        var registry = new InMemoryEventTypeRegistry();
        var descriptor = new EventTypeDescriptor("test.event", "Test", "desc", List.of());
        registry.register(descriptor);
        var service = new EventTypeService(registry);
        var controller = new EventTypeRestController(service);

        var result = controller.listEventTypes();
        assertThat(result).containsExactly(descriptor);
    }
}
