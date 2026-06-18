package io.casehub.platform.streams.camel;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CamelStreamProcessorTest {

    private final CamelStreamProcessor processor = new CamelStreamProcessor();

    private EndpointDescriptor descriptor(String uri, String streamType) {
        return new EndpointDescriptor(
            Path.of("streams", "camel-data"),
            TenancyConstants.DEFAULT_TENANT_ID,
            EndpointType.WORKER,
            EndpointProtocol.CAMEL,
            Map.of(EndpointPropertyKeys.URL, uri,
                   EndpointPropertyKeys.STREAM_EVENT_TYPE, streamType),
            null,
            Set.of(EndpointCapability.RECEIVE));
    }

    @Test
    void buildCloudEvent_type_from_descriptor() {
        CloudEvent ce = processor.buildCloudEvent(new byte[0], descriptor("direct:test", "io.casehub.camel.event"));
        assertThat(ce.getType()).isEqualTo("io.casehub.camel.event");
    }

    @Test
    void buildCloudEvent_tenancyid_from_descriptor() {
        CloudEvent ce = processor.buildCloudEvent(new byte[0], descriptor("direct:test", "io.casehub.camel.event"));
        assertThat(ce.getExtension("tenancyid")).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void buildCloudEvent_source_contains_camel() {
        CloudEvent ce = processor.buildCloudEvent(new byte[0], descriptor("direct:test", "io.casehub.camel.event"));
        assertThat(ce.getSource().toString()).contains("camel");
    }

    @Test
    void buildCloudEvent_data_is_raw_bytes() {
        byte[] payload = "payload".getBytes();
        CloudEvent ce = processor.buildCloudEvent(payload, descriptor("direct:test", "io.casehub.camel.event"));
        assertThat(ce.getData().toBytes()).isEqualTo(payload);
    }
}
