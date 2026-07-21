package io.casehub.platform.event;

import io.casehub.platform.api.event.CloudEventType;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class CloudEventTypeDispatcherTest {

    private List<Annotation> selectedQualifiers;
    private List<CloudEvent> firedEvents;
    private CloudEventTypeDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        selectedQualifiers = new ArrayList<>();
        firedEvents = new ArrayList<>();

        Event<CloudEvent> mockBus = new StubEvent<>() {
            @Override
            public Event<CloudEvent> select(Annotation... qualifiers) {
                selectedQualifiers.addAll(List.of(qualifiers));
                return this;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <U extends CloudEvent> CompletionStage<U> fireAsync(U event) {
                firedEvents.add(event);
                return CompletableFuture.completedFuture(event);
            }
        };

        dispatcher = new CloudEventTypeDispatcher(mockBus);
    }

    private CloudEvent cloudEvent(String type) {
        return CloudEventBuilder.v1()
                .withId("test-1")
                .withSource(URI.create("/test"))
                .withType(type)
                .build();
    }

    @Test
    void dispatches_withCorrectQualifier() {
        dispatcher.onCloudEvent(cloudEvent("io.casehub.cbr.outcome"));

        assertThat(selectedQualifiers).hasSize(1);
        assertThat(selectedQualifiers.get(0)).isInstanceOf(CloudEventType.class);
        assertThat(((CloudEventType) selectedQualifiers.get(0)).value())
                .isEqualTo("io.casehub.cbr.outcome");
        assertThat(firedEvents).hasSize(1);
    }

    @Test
    void dispatches_preservesOriginalEvent() {
        CloudEvent original = cloudEvent("io.casehub.cbr.outcome");
        dispatcher.onCloudEvent(original);

        assertThat(firedEvents.get(0)).isSameAs(original);
    }

    @Test
    void skips_blankType() {
        var ce = CloudEventBuilder.v1()
                .withId("test-1")
                .withSource(URI.create("/test"))
                .withType("   ")
                .build();
        dispatcher.onCloudEvent(ce);

        assertThat(firedEvents).isEmpty();
        assertThat(selectedQualifiers).isEmpty();
    }

    @Test
    void literal_equalityByValue() {
        var a = new CloudEventTypeLiteral("io.casehub.cbr.outcome");
        var b = new CloudEventTypeLiteral("io.casehub.cbr.outcome");
        var c = new CloudEventTypeLiteral("io.casehub.other");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void literal_value() {
        var literal = new CloudEventTypeLiteral("io.casehub.cbr.outcome");
        assertThat(literal.value()).isEqualTo("io.casehub.cbr.outcome");
    }

    static abstract class StubEvent<T> implements Event<T> {
        @Override
        public void fire(T event)                                                                                        {throw new UnsupportedOperationException();}

        @Override
        public <U extends T> CompletionStage<U> fireAsync(U event)                                                       {throw new UnsupportedOperationException();}

        @Override
        public <U extends T> CompletionStage<U> fireAsync(U event, jakarta.enterprise.event.NotificationOptions options) {throw new UnsupportedOperationException();}

        @Override
        public Event<T> select(Annotation... qualifiers)                                                                 {throw new UnsupportedOperationException();}

        @Override
        public <U extends T> Event<U> select(Class<U> subtype, Annotation... qualifiers)                                 {throw new UnsupportedOperationException();}

        @Override
        public <U extends T> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers)                           {throw new UnsupportedOperationException();}
    }
}
