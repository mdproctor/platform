package io.casehub.platform.streams.webhook;

import io.casehub.platform.api.mcp.McpDomain;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

@Path("/streams/webhook")
@McpDomain(value = "casehub/streams-webhook", app = "platform",
        summary = "CloudEvents webhook receiver — accepts structured CloudEvents via HTTP POST",
        basePath = "/streams/webhook")
public class WebhookResource {

    @Inject
    WebhookService webhookService;

    @POST
    @Path("/{tenancyId}/{streamId}")
    @Consumes("application/cloudevents+json")
    public Response receive(byte[] body,
                            @PathParam("tenancyId") String tenancyId,
                            @PathParam("streamId") String streamId,
                            @HeaderParam("Authorization") String authorization) {
        WebhookResult result = webhookService.receive(body, tenancyId, streamId, authorization);
        if (result.errorMessage() != null) {
            return Response.status(result.status()).entity(result).build();
        }
        return Response.status(result.status()).build();
    }
}
