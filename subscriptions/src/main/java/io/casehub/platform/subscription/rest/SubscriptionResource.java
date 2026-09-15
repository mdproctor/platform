package io.casehub.platform.subscription.rest;

import io.casehub.platform.api.subscription.SubscriptionPage;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionScope;
import io.casehub.platform.api.subscription.SubscriptionUpdate;
import io.casehub.platform.subscription.SubscriptionService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/subscriptions")
@RunOnVirtualThread
public class SubscriptionResource {

    private final SubscriptionService service;

    @Inject
    public SubscriptionResource(final SubscriptionService service) {
        this.service = service;
    }

    @POST
    public Response create(final SubscriptionInput input) {
        try {
            return Response.status(201).entity(service.create(input)).build();
        } catch (SecurityException e) {
            return Response.status(403).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(e.getMessage()).build();
        }
    }

    @GET
    public SubscriptionPage list(
            @QueryParam("enabled") final Boolean enabled,
            @QueryParam("scope") final SubscriptionScope scope,
            @QueryParam("cursor") final String cursor,
            @QueryParam("limit") @DefaultValue("25") final int limit) {
        return service.list(enabled, scope, cursor, limit);
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") final String id) {
        return service.getById(id)
                .map(s -> Response.ok(s).build())
                .orElse(Response.status(404).build());
    }

    @PATCH
    @Path("/{id}")
    public Response update(@PathParam("id") final String id, final SubscriptionUpdate update) {
        try {
            return service.update(id, update)
                    .map(s -> Response.ok(s).build())
                    .orElse(Response.status(404).build());
        } catch (SecurityException e) {
            return Response.status(403).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") final String id) {
        try {
            return service.delete(id)
                    ? Response.noContent().build()
                    : Response.status(404).build();
        } catch (SecurityException e) {
            return Response.status(403).build();
        }
    }

    @PATCH
    @Path("/{id}/enable")
    public Response enable(@PathParam("id") final String id) {
        try {
            return service.enable(id)
                    .map(s -> Response.ok(s).build())
                    .orElse(Response.status(404).build());
        } catch (SecurityException e) {
            return Response.status(403).build();
        }
    }

    @PATCH
    @Path("/{id}/disable")
    public Response disable(@PathParam("id") final String id) {
        try {
            return service.disable(id)
                    .map(s -> Response.ok(s).build())
                    .orElse(Response.status(404).build());
        } catch (SecurityException e) {
            return Response.status(403).build();
        }
    }
}
