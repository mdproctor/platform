package io.casehub.platform.subscription;

import io.casehub.platform.api.subscription.EventFieldDescriptor;
import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.api.subscription.EventTypeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryEventTypeRegistryTest {

    private EventTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryEventTypeRegistry();
    }

    @Test
    void discoverReturnsEmptyWhenNothingRegistered() {
        assertThat(registry.discover()).isEmpty();
    }

    @Test
    void resolveReturnsEmptyWhenNotRegistered() {
        assertThat(registry.resolve("nonexistent")).isEmpty();
    }

    @Test
    void registerAndResolve() {
        var descriptor = descriptor("io.casehub.work.workitem.completed", "Work Item Completed");
        registry.register(descriptor);
        assertThat(registry.resolve("io.casehub.work.workitem.completed")).contains(descriptor);
    }

    @Test
    void registerAndDiscover() {
        var d1 = descriptor("type.a", "Type A");
        var d2 = descriptor("type.b", "Type B");
        registry.register(d1);
        registry.register(d2);
        assertThat(registry.discover()).containsExactlyInAnyOrder(d1, d2);
    }

    @Test
    void registerReplacesExistingEventType() {
        var original = descriptor("type.a", "Original");
        var replacement = descriptor("type.a", "Replacement");
        registry.register(original);
        registry.register(replacement);
        assertThat(registry.resolve("type.a")).contains(replacement);
        assertThat(registry.discover()).hasSize(1);
    }

    @Test
    void registerRejectsNull() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void resolveRejectsNull() {
        assertThatThrownBy(() -> registry.resolve(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static EventTypeDescriptor descriptor(final String eventType, final String displayName) {
        return new EventTypeDescriptor(eventType, displayName, null, List.of(
                new EventFieldDescriptor("id", "ID", "string")));
    }
}
