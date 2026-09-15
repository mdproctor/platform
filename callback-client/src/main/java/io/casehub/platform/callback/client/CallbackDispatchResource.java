package io.casehub.platform.callback.client;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/casehub/callbacks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CallbackDispatchResource {

    private final CallbackDispatcher dispatcher;

    @Inject
    public CallbackDispatchResource(CallbackDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @POST
    @Path("/{spiName}/{methodName}")
    public Response dispatch(@PathParam("spiName") final String spiName,
                             @PathParam("methodName") final String methodName,
                             @HeaderParam("X-CaseHub-SPI") final String spiHeader,
                             final JsonNode argsNode) {
        DispatchResult result = dispatcher.dispatch(spiName, methodName, spiHeader, argsNode);
        if (result.body() == null) {
            return Response.status(result.status()).build();
        }
        return Response.status(result.status()).entity(result.body()).build();
    }
}
