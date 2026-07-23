package io.casehub.platform.notification.rest;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.notification.NotificationPage;
import io.casehub.platform.api.notification.NotificationQuery;
import io.casehub.platform.api.notification.NotificationStatus;
import io.casehub.platform.api.notification.NotificationStore;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@ApplicationScoped
@Path("/notifications")
@RunOnVirtualThread
public class NotificationResource {

    private final NotificationStore store;
    private final CurrentPrincipal  principal;

    @Inject
    public NotificationResource(NotificationStore store, CurrentPrincipal principal) {
        this.store     = store;
        this.principal = principal;
    }

    @GET
    public NotificationPage list(
            @QueryParam("status") NotificationStatus status,
            @QueryParam("category") String category,
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") Integer limit) {

        return store.find(new NotificationQuery(
                principal.actorId(),
                principal.tenancyId(),
                status,
                category,
                cursor,
                limit != null ? limit : 25
        ));
    }

    @GET
    @Path("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", store.unreadCount(principal.actorId(), principal.tenancyId()));
    }

    @PATCH
    @Path("/{id}/read")
    public Response markRead(@PathParam("id") String id) {
        return store.markRead(id, principal.actorId(), principal.tenancyId())
                    .map(notification -> Response.ok(notification).build())
                    .orElse(Response.status(404).build());
    }

    @PATCH
    @Path("/{id}/dismiss")
    public Response dismiss(@PathParam("id") String id) {
        return store.dismiss(id, principal.actorId(), principal.tenancyId())
                    .map(notification -> Response.ok(notification).build())
                    .orElse(Response.status(404).build());
    }

    @POST
    @Path("/mark-all-read")
    public Map<String, Integer> markAllRead() {
        return Map.of("count", store.markAllRead(principal.actorId(), principal.tenancyId()));
    }
}
