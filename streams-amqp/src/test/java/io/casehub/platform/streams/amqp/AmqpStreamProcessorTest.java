package io.casehub.platform.streams.amqp;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AmqpStreamProcessorTest {

    private final AmqpStreamProcessor processor = new AmqpStreamProcessor();

    private EndpointDescriptor descriptor(String queue, String streamType) {
        return new EndpointDescriptor(
            Path.of("streams", "amqp-events"),
            TenancyConstants.DEFAULT_TENANT_ID,
            EndpointType.SYSTEM,
            EndpointProtocol.AMQP,
            Map.of(EndpointPropertyKeys.TOPIC, queue,
                   EndpointPropertyKeys.STREAM_EVENT_TYPE, streamType),
            null,
            Set.of(EndpointCapability.RECEIVE));
    }

    @Test
    void buildCloudEvent_sets_type_from_descriptor() {
        CloudEvent ce = processor.buildCloudEvent(new byte[0], descriptor("orders", "io.casehub.orders.placed"), "tenant-a");
        assertThat(ce.getType()).isEqualTo("io.casehub.orders.placed");
    }

    @Test
    void buildCloudEvent_uses_header_tenancyid_when_present() {
        CloudEvent ce = processor.buildCloudEvent(new byte[0], descriptor("orders", "io.casehub.orders.placed"), "hdr-tenant");
        assertThat(ce.getExtension("tenancyid")).isEqualTo("hdr-tenant");
    }

    @Test
    void buildCloudEvent_falls_back_to_descriptor_tenancyid_when_header_absent() {
        CloudEvent ce = processor.buildCloudEvent(new byte[0], descriptor("orders", "io.casehub.orders.placed"), null);
        assertThat(ce.getExtension("tenancyid")).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void buildCloudEvent_source_is_amqp_prefixed() {
        CloudEvent ce = processor.buildCloudEvent(new byte[0], descriptor("orders", "io.casehub.orders.placed"), null);
        assertThat(ce.getSource().toString()).startsWith("/platform/streams/amqp/");
    }

    @Test
    void buildCloudEvent_data_contains_raw_bytes() {
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        CloudEvent ce = processor.buildCloudEvent(payload, descriptor("orders", "io.casehub.orders.placed"), null);
        assertThat(new String(ce.getData().toBytes(), StandardCharsets.UTF_8)).isEqualTo("test");
    }
}
