package io.casehub.platform.preferences.editor;

import io.casehub.platform.api.mcp.McpDomain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/preferences/schema")
@McpDomain("preference-schemas")
public class PreferenceSchemaResource {

    @Inject PreferenceSchemaService schemaService;

    @GET
    public Response schema(@QueryParam("namespace") String namespace,
                           @Context Request request) {
        var result = schemaService.schema(namespace);
        EntityTag etag = new EntityTag(result.version());
        Response.ResponseBuilder notModified = request.evaluatePreconditions(etag);
        if (notModified != null) {
            return notModified.build();
        }
        return Response.ok(result.schemas()).tag(etag).build();
    }
}
