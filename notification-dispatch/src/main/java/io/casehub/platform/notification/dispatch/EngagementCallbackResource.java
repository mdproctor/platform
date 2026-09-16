package io.casehub.platform.notification.dispatch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

@Path("/delivery/engagement")
@ApplicationScoped
public class EngagementCallbackResource {

    private static final Logger LOG = Logger.getLogger(EngagementCallbackResource.class);

    private final EngagementCallbackService service;

    @Context
    HttpHeaders httpHeaders;

    @Inject
    public EngagementCallbackResource(EngagementCallbackService service) {
        this.service = service;
    }

    EngagementCallbackResource(EngagementCallbackService service, HttpHeaders httpHeaders) {
        this.service = service;
        this.httpHeaders = httpHeaders;
    }

    @POST
    @Path("/callback/{channelId}")
    @Consumes({"application/json", "application/x-www-form-urlencoded"})
    public Response handleCallback(@PathParam("channelId") String channelId, String rawPayload) {
        try {
            service.handleCallback(channelId, rawPayload, extractHeaders());
            return Response.ok().build();
        } catch (IllegalStateException e) {
            return Response.status(404).build();
        } catch (IllegalArgumentException e) {
            return Response.status(404).build();
        } catch (SecurityException e) {
            LOG.warnf("Engagement callback handler '%s' rejected payload: %s", channelId, e.getMessage());
            return Response.status(401).build();
        } catch (Exception e) {
            LOG.warnf(e, "Engagement callback handler '%s' failed", channelId);
            return Response.ok().build();
        }
    }

    @POST
    @Path("/{attemptId}")
    @Consumes("application/json")
    public Response recordDirect(@PathParam("attemptId") String attemptId,
                                 DirectEngagementRequest request) {
        try {
            service.recordDirect(attemptId, request);
            return Response.ok().build();
        } catch (IllegalStateException e) {
            return Response.status(404).build();
        } catch (IllegalArgumentException e) {
            return Response.status(e.getMessage().contains("not found") ? 404 : 400).build();
        }
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
