package io.casehub.platform.api.endpoints;

import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointRegisteredTest {

    @Test
    void endpointRegistered_carries_descriptor() {
        var desc = new EndpointDescriptor(
            Path.of("platform", "streams", "webhook"),
            "platform",
            EndpointType.SERVICE,
            EndpointProtocol.HTTP,
            Map.of(),
            null,
            Set.of(EndpointCapability.RECEIVE));

        var event = new EndpointRegistered(desc);

        assertThat(event.descriptor()).isSameAs(desc);
    }

    @Test
    void endpointProtocol_amqp_exists() {
        assertThat(EndpointProtocol.valueOf("AMQP")).isEqualTo(EndpointProtocol.AMQP);
    }

    @Test
    void streamEventTypeKey_value() {
        assertThat(EndpointPropertyKeys.STREAM_EVENT_TYPE).isEqualTo("stream-event-type");
    }
}
