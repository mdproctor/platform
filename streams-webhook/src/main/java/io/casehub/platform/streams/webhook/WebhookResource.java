package io.casehub.platform.streams.webhook;

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
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CloudEvents HTTP binding receiver.
 *
 * <p>Accepts structured CloudEvents ({@code application/cloudevents+json}) only (P0).
 * Binary CloudEvents (ce-* headers) deferred to P1+.
 *
 * <p>{@link #publicUrl} is required — Quarkus throws {@code DeploymentException} at
 * startup if {@code casehub.streams.webhook.public-url} is absent.
 *
 * <p>Incoming CloudEvent fields are preserved; only {@code tenancyid} is set/replaced
 * from the registered {@link EndpointDescriptor} (caller-supplied value is overridden).
 *
 * <p>{@code @Startup} forces eager {@code @PostConstruct} so the EventFormat is resolved and
 * the physical receiver is registered before the first HTTP request arrives.
 */
@Startup
@ApplicationScoped
@jakarta.ws.rs.Path("/streams/webhook")
public class WebhookResource {

    private static final Logger LOG = Logger.getLogger(WebhookResource.class);

    @Inject
    Event<CloudEvent> cloudEventBus;

    @Inject
    EndpointRegistry endpointRegistry;

    @ConfigProperty(name = "casehub.streams.webhook.public-url")
    String publicUrl;   // required — Quarkus DeploymentException at startup if absent

    private EventFormat eventFormat;

    @PostConstruct
    void init() {
        // Validate CloudEvents format registration
        eventFormat = EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);
        if (eventFormat == null) {
            throw new IllegalStateException(
                "CloudEvents JSON format not registered — cloudevents-json-jackson missing from classpath");
        }

        // Self-register the physical webhook receiver as a platform-global endpoint.
        // PLATFORM_TENANT_ID makes it visible in all tenant-scoped discover() calls.
        endpointRegistry.register(new EndpointDescriptor(
            Path.of("platform", "streams", "webhook"),
            TenancyConstants.PLATFORM_TENANT_ID,
            EndpointType.SERVICE,
            EndpointProtocol.HTTP,
            Map.of(EndpointPropertyKeys.URL, publicUrl),
            null,
            Set.of(EndpointCapability.RECEIVE)));
    }

    @POST
    @jakarta.ws.rs.Path("/{tenancyId}/{streamId}")
    @Consumes("application/cloudevents+json")
    public Response receive(
            byte[] body,
            @PathParam("tenancyId") String tenancyIdFromPath,
            @PathParam("streamId") String streamId) {

        CloudEvent incoming;
        try {
            incoming = eventFormat.deserialize(body);
        } catch (RuntimeException e) {
            return Response.status(400)
                .entity("Invalid CloudEvent body: " + e.getMessage())
                .build();
        }

        Optional<EndpointDescriptor> descriptor =
            endpointRegistry.resolve(Path.of("streams", streamId), tenancyIdFromPath);
        if (descriptor.isEmpty()) {
            return Response.status(404).build();
        }

        // Preserve all incoming fields; set/replace tenancyid from operator-authoritative descriptor.
        CloudEvent enriched = CloudEventBuilder.from(incoming)
            .withExtension("tenancyid", descriptor.get().tenancyId())
            .build();

        cloudEventBus.fireAsync(enriched)
            .whenComplete((e, t) -> {
                if (t != null) LOG.warnf(t, "CloudEvent observer failed for stream %s", streamId);
            });

        return Response.accepted().build();
    }
}
