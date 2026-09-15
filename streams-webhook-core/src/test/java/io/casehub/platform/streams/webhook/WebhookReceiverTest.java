package io.casehub.platform.streams.webhook;

import io.casehub.platform.api.credentials.CredentialPropertyKeys;
import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.path.Path;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookReceiverTest {

    private final List<CloudEvent> firedEvents = new ArrayList<>();
    private final StubEndpointRegistry registry = new StubEndpointRegistry();
    private final StubCredentialResolver credResolver = new StubCredentialResolver();

    private WebhookReceiver receiver;

    @BeforeEach
    void setUp() {
        receiver = new WebhookReceiver(registry, credResolver, firedEvents::add,
                "http://localhost:8080", false);
        receiver.init();
    }

    @Test
    void receive_valid_event_returns_accepted() {
        registry.descriptor = new EndpointDescriptor(
                Path.of("streams", "test-stream"), "t1", EndpointType.SERVICE,
                EndpointProtocol.HTTP, Map.of(), null, Set.of(EndpointCapability.RECEIVE));

        byte[] body = serializeCloudEvent();
        var result = receiver.receive(body, "t1", "test-stream", Map.of());
        assertThat(result.status()).isEqualTo(202);
        assertThat(firedEvents).hasSize(1);
    }

    @Test
    void receive_invalid_body_returns_bad_request() {
        var result = receiver.receive("not-json".getBytes(), "t1", "test-stream", Map.of());
        assertThat(result.status()).isEqualTo(400);
    }

    @Test
    void receive_unknown_stream_returns_not_found() {
        byte[] body = serializeCloudEvent();
        var result = receiver.receive(body, "t1", "unknown", Map.of());
        assertThat(result.status()).isEqualTo(404);
    }

    @Test
    void receive_missing_auth_returns_unauthorized() {
        registry.descriptor = new EndpointDescriptor(
                Path.of("streams", "test-stream"), "t1", EndpointType.SERVICE,
                EndpointProtocol.HTTP, Map.of(), "cred-ref", Set.of(EndpointCapability.RECEIVE));
        credResolver.creds = Map.of(CredentialPropertyKeys.BEARER_TOKEN, "secret-token");

        byte[] body = serializeCloudEvent();
        var result = receiver.receive(body, "t1", "test-stream", Map.of());
        assertThat(result.status()).isEqualTo(401);
    }

    @Test
    void receive_valid_auth_passes() {
        registry.descriptor = new EndpointDescriptor(
                Path.of("streams", "test-stream"), "t1", EndpointType.SERVICE,
                EndpointProtocol.HTTP, Map.of(), "cred-ref", Set.of(EndpointCapability.RECEIVE));
        credResolver.creds = Map.of(CredentialPropertyKeys.BEARER_TOKEN, "secret-token");

        byte[] body = serializeCloudEvent();
        var result = receiver.receive(body, "t1", "test-stream",
                Map.of("Authorization", "Bearer secret-token"));
        assertThat(result.status()).isEqualTo(202);
    }

    @Test
    void receive_no_credential_ref_and_require_auth_returns_unauthorized() {
        registry.descriptor = new EndpointDescriptor(
                Path.of("streams", "test-stream"), "t1", EndpointType.SERVICE,
                EndpointProtocol.HTTP, Map.of(), null, Set.of(EndpointCapability.RECEIVE));

        receiver = new WebhookReceiver(registry, credResolver, firedEvents::add,
                "http://localhost:8080", true);
        receiver.init();

        byte[] body = serializeCloudEvent();
        var result = receiver.receive(body, "t1", "test-stream", Map.of());
        assertThat(result.status()).isEqualTo(401);
    }

    private byte[] serializeCloudEvent() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("test-id")
                .withSource(URI.create("http://test"))
                .withType("test.event")
                .build();
        EventFormat format = EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);
        return format.serialize(event);
    }

    static class StubEndpointRegistry implements EndpointRegistry {
        EndpointDescriptor descriptor;

        @Override public void register(EndpointDescriptor d) {}
        @Override public Optional<EndpointDescriptor> resolve(Path path, String tenancyId) {
            if (descriptor != null && descriptor.path().equals(path)) {
                return Optional.of(descriptor);
            }
            return Optional.empty();
        }
        @Override public List<EndpointDescriptor> discover(EndpointQuery query) { return List.of(); }
        @Override public void deregister(Path path, String tenancyId) {}
    }

    static class StubCredentialResolver implements CredentialResolver {
        Map<String, String> creds = Map.of();
        @Override public Map<String, String> resolve(String credentialRef) { return creds; }
    }
}
