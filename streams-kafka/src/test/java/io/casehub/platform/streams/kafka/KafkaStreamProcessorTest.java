package io.casehub.platform.streams.kafka;

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

/**
 * Unit tests for KafkaStreamProcessor.buildCloudEvent().
 * CDI async delivery is untestable in @QuarkusTest (GE-20260513-b15933);
 * we test the construction logic directly via the package-private method.
 */
class KafkaStreamProcessorTest {

    private final KafkaStreamProcessor processor = new KafkaStreamProcessor();

    private EndpointDescriptor descriptor(String streamType) {
        return new EndpointDescriptor(
            Path.of("streams", "iot-events"),
            TenancyConstants.DEFAULT_TENANT_ID,
            EndpointType.SYSTEM,
            EndpointProtocol.KAFKA,
            Map.of(EndpointPropertyKeys.TOPIC, "iot-temperature",
                   EndpointPropertyKeys.STREAM_EVENT_TYPE, streamType),
            null,
            Set.of(EndpointCapability.RECEIVE));
    }

    @Test
    void buildCloudEvent_sets_type_from_descriptor() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        CloudEvent ce = processor.buildCloudEvent(payload, "iot-temperature", descriptor("io.casehub.iot.temperature"), "tenant-a");
        assertThat(ce.getType()).isEqualTo("io.casehub.iot.temperature");
    }

    @Test
    void buildCloudEvent_sets_tenancyid_from_header_when_present() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        CloudEvent ce = processor.buildCloudEvent(payload, "iot-temperature", descriptor("io.casehub.iot.temperature"), "header-tenant");
        assertThat(ce.getExtension("tenancyid")).isEqualTo("header-tenant");
    }

    @Test
    void buildCloudEvent_falls_back_to_descriptor_tenancyid_when_header_absent() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        CloudEvent ce = processor.buildCloudEvent(payload, "iot-temperature", descriptor("io.casehub.iot.temperature"), null);
        assertThat(ce.getExtension("tenancyid")).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void buildCloudEvent_sets_data_to_raw_bytes() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        CloudEvent ce = processor.buildCloudEvent(payload, "iot-temperature", descriptor("io.casehub.iot.temperature"), null);
        assertThat(ce.getData()).isNotNull();
        assertThat(new String(ce.getData().toBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void buildCloudEvent_source_contains_topic() {
        byte[] payload = new byte[0];
        CloudEvent ce = processor.buildCloudEvent(payload, "iot-temperature", descriptor("io.casehub.iot.temperature"), null);
        assertThat(ce.getSource().toString()).contains("iot-temperature");
    }

    @Test
    void buildCloudEvent_unregistered_type_used_when_no_descriptor() {
        byte[] payload = new byte[0];
        CloudEvent ce = processor.buildCloudEvent(payload, "unknown-topic", null, null);
        assertThat(ce.getType()).isEqualTo("io.casehub.platform.streams.kafka.unregistered");
    }
}
