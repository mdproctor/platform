package io.casehub.platform.streams.webhook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
@jakarta.ws.rs.Path("/streams/webhook")
public class WebhookResource {

    private final WebhookReceiver receiver;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    public WebhookResource(WebhookReceiver receiver) {
        this.receiver = receiver;
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
        if (httpHeaders == null) {return Map.of();}
        Map<String, String> result = new HashMap<>();
        for (var key : httpHeaders.getRequestHeaders().keySet()) {
            result.put(key, httpHeaders.getHeaderString(key));
        }
        return result;
    }
}
