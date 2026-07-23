package io.casehub.platform.preferences.editor;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.PreferenceQuery;
import io.casehub.platform.api.preferences.PreferenceRecord;
import io.casehub.platform.api.preferences.PreferenceStore;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@jakarta.ws.rs.Path("/preferences")
public class PreferenceResource {

    @Inject PreferenceStore store;
    @Inject PreferenceProvider provider;
    @Inject CurrentPrincipal principal;

    @PUT
    public Response set(@QueryParam("scope") String scopeParam, PreferenceInput input) {
        Path scope = parseScopePath(scopeParam);
        store.set(principal.tenancyId(), scope, input.namespace(), input.name(), input.subKey(), input.value());
        return Response.noContent().build();
    }

    @DELETE
    public Response delete(@QueryParam("scope") String scopeParam,
                           @QueryParam("namespace") String namespace,
                           @QueryParam("name") String name,
                           @QueryParam("subKey") String subKey) {
        if (name == null || name.isBlank()) {
            return Response.status(400).entity("name is required for single-delete").build();
        }
        if (namespace == null || namespace.isBlank()) {
            return Response.status(400).entity("namespace is required for single-delete").build();
        }
        Path scope = parseScopePath(scopeParam);
        store.delete(principal.tenancyId(), scope, namespace, name, subKey != null ? subKey : "");
        return Response.noContent().build();}

    @DELETE
    @jakarta.ws.rs.Path("/by-namespace")
    public Response deleteNamespace(@QueryParam("scope") String scopeParam,
                                    @QueryParam("namespace") String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return Response.status(400).entity("namespace is required").build();
        }
        Path scope = parseScopePath(scopeParam);
        store.deleteAll(principal.tenancyId(), scope, namespace);
        return Response.noContent().build();}

    @GET
    public List<PreferenceRecord> list(@QueryParam("scope") String scopeParam) {
        Path scope = parseScopePath(scopeParam);
        return store.list(new PreferenceQuery(principal.tenancyId(), scope, null));
    }

    @GET
    @jakarta.ws.rs.Path("/resolved")
    public ResolvedPreferencesResponse resolved(@QueryParam("scope") String scopeParam) {
        Path scope = parseScopePath(scopeParam);
        Preferences resolved = provider.resolve(SettingsScope.of(principal.tenancyId(), scope));
        Map<String, String> values = new HashMap<>();
        resolved.asMap().forEach((k, v) -> values.put(k, String.valueOf(v)));
        return new ResolvedPreferencesResponse(scopeParam != null ? scopeParam : "", values);
    }

    private static Path parseScopePath(String scopeParam) {
        if (scopeParam == null || scopeParam.isBlank()) return Path.root();
        return Path.of(scopeParam.split("/"));
    }
}
