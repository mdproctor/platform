package io.casehub.platform.streams.webhook;

import io.casehub.platform.api.credentials.CredentialPropertyKeys;
import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public class WebhookReceiver {

    private final EndpointRegistry endpointRegistry;
    private final CredentialResolver credentialResolver;
    private final Consumer<CloudEvent> eventCallback;
    private final String publicUrl;
    private final boolean requireAuth;
    private EventFormat eventFormat;

    public WebhookReceiver(EndpointRegistry endpointRegistry,
                           CredentialResolver credentialResolver,
                           Consumer<CloudEvent> eventCallback,
                           String publicUrl,
                           boolean requireAuth) {
        this.endpointRegistry = endpointRegistry;
        this.credentialResolver = credentialResolver;
        this.eventCallback = eventCallback;
        this.publicUrl = publicUrl;
        this.requireAuth = requireAuth;
    }

    public void init() {
        eventFormat = EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);
        if (eventFormat == null) {
            throw new IllegalStateException(
                    "CloudEvents JSON format not registered — cloudevents-json-jackson missing from classpath");
        }
        endpointRegistry.register(new EndpointDescriptor(
                Path.of("platform", "streams", "webhook"),
                TenancyConstants.PLATFORM_TENANT_ID,
                EndpointType.SERVICE,
                EndpointProtocol.HTTP,
                Map.of(EndpointPropertyKeys.URL, publicUrl),
                null,
                Set.of(EndpointCapability.RECEIVE)));
    }

    public WebhookResult receive(byte[] body, String tenancyId, String streamId, Map<String, String> headers) {
        CloudEvent incoming;
        try {
            incoming = eventFormat.deserialize(body);
        } catch (RuntimeException e) {
            return WebhookResult.badRequest("Invalid CloudEvent body: " + e.getMessage());
        }

        Optional<EndpointDescriptor> descriptor =
                endpointRegistry.resolve(Path.of("streams", streamId), tenancyId);
        if (descriptor.isEmpty()) {
            return WebhookResult.notFound();
        }

        WebhookResult authFailure = validateCredentials(descriptor.get(), streamId, headers);
        if (authFailure != null) {
            return authFailure;
        }

        CloudEvent enriched = CloudEventBuilder.from(incoming)
                .withExtension("tenancyid", descriptor.get().tenancyId())
                .build();

        eventCallback.accept(enriched);
        return WebhookResult.accepted();
    }

    private WebhookResult validateCredentials(EndpointDescriptor descriptor, String streamId, Map<String, String> headers) {
        if (descriptor.credentialRef() != null) {
            Map<String, String> creds = credentialResolver.resolve(descriptor.credentialRef());
            String expectedToken = creds.get(CredentialPropertyKeys.BEARER_TOKEN);
            if (expectedToken != null) {
                String authHeader = headers.get("Authorization");
                if (authHeader == null || !authHeader.equals("Bearer " + expectedToken)) {
                    return WebhookResult.unauthorized();
                }
            }
        } else if (requireAuth) {
            return WebhookResult.unauthorized();
        }
        return null;
    }
}
