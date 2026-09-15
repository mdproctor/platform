package io.casehub.platform.streams.webhook;

import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.cloudevents.CloudEvent;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

@Startup
@ApplicationScoped
@jakarta.ws.rs.Path("/streams/webhook")
public class WebhookResource {

    private static final Logger LOG = Logger.getLogger(WebhookResource.class);

    private final WebhookReceiver receiver;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    public WebhookResource(EndpointRegistry endpointRegistry,
                           CredentialResolver credentialResolver,
                           Event<CloudEvent> cloudEventBus,
                           @ConfigProperty(name = "casehub.streams.webhook.public-url") String publicUrl,
                           @ConfigProperty(name = "casehub.streams.webhook.require-auth", defaultValue = "true") boolean requireAuth) {
        this.receiver = new WebhookReceiver(endpointRegistry, credentialResolver,
                event -> cloudEventBus.fireAsync(event)
                        .whenComplete((e, t) -> {
                            if (t != null) { LOG.warnf(t, "CloudEvent observer failed"); }
                        }),
                publicUrl, requireAuth);
    }

    @PostConstruct
    void init() {
        receiver.init();
    }

    @POST
    @jakarta.ws.rs.Path("/{tenancyId}/{streamId}")
    @Consumes("application/cloudevents+json")
    public Response receive(byte[] body,
                            @PathParam("tenancyId") String tenancyId,
                            @PathParam("streamId") String streamId) {
        WebhookResult result = receiver.receive(body, tenancyId, streamId, extractHeaders());
        if (result.errorMessage() != null) {
            return Response.status(result.status()).entity(result.errorMessage()).build();
        }
        return Response.status(result.status()).build();
    }

    private Map<String, String> extractHeaders() {
        if (httpHeaders == null) { return Map.of(); }
        Map<String, String> result = new HashMap<>();
        for (var key : httpHeaders.getRequestHeaders().keySet()) {
            result.put(key, httpHeaders.getHeaderString(key));
        }
        return result;
    }
}
