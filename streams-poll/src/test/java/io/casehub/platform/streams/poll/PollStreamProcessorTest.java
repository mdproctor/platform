package io.casehub.platform.streams.poll;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollStreamProcessorTest {

    private WireMockServer wireMock;
    private PollStreamProcessor processor;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        processor = new PollStreamProcessor();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private EndpointDescriptor descriptor(String url, String streamType) {
        return new EndpointDescriptor(
            Path.of("streams", "sensor-data"),
            TenancyConstants.DEFAULT_TENANT_ID,
            EndpointType.SYSTEM,
            EndpointProtocol.HTTP,
            Map.of(EndpointPropertyKeys.URL, url,
                   EndpointPropertyKeys.STREAM_EVENT_TYPE, streamType),
            null,
            Set.of(EndpointCapability.QUERY));
    }

    @Test
    void buildCloudEvent_type_from_descriptor() throws IOException {
        byte[] body = "{\"temp\":22}".getBytes();
        EndpointDescriptor desc = descriptor("http://localhost/data", "io.casehub.sensor.temperature");

        CloudEvent ce = processor.buildCloudEvent(body, desc);

        assertThat(ce.getType()).isEqualTo("io.casehub.sensor.temperature");
    }

    @Test
    void buildCloudEvent_tenancyid_from_descriptor() throws IOException {
        CloudEvent ce = processor.buildCloudEvent(new byte[0],
            descriptor("http://localhost/data", "io.casehub.sensor.temperature"));

        assertThat(ce.getExtension("tenancyid")).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void buildCloudEvent_source_is_poll_prefixed() throws IOException {
        CloudEvent ce = processor.buildCloudEvent(new byte[0],
            descriptor("http://example.com/api", "io.casehub.test"));

        assertThat(ce.getSource().toString()).startsWith("/platform/streams/poll/");
    }

    @Test
    void pollAndFire_throws_IOException_on_non_2xx() {
        wireMock.stubFor(get(urlEqualTo("/data"))
            .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        EndpointDescriptor desc = descriptor("http://localhost:" + wireMock.port() + "/data",
            "io.casehub.sensor.temperature");

        assertThatThrownBy(() -> processor.pollAndFire(desc))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("503");
    }

    @Test
    void pollAndFire_throws_IOException_on_404() {
        wireMock.stubFor(get(urlEqualTo("/data"))
            .willReturn(aResponse().withStatus(404)));

        EndpointDescriptor desc = descriptor("http://localhost:" + wireMock.port() + "/data",
            "io.casehub.sensor.temperature");

        assertThatThrownBy(() -> processor.pollAndFire(desc))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("404");
    }
}
