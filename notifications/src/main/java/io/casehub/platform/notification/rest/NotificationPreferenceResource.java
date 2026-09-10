package io.casehub.platform.notification.rest;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.notification.settings.NotificationPreferenceStore;
import io.casehub.platform.api.notification.settings.NotificationPreferenceUpdate;
import io.casehub.platform.api.notification.settings.NotificationPreferences;
import io.casehub.platform.notification.PreferenceValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

import java.time.Instant;
import java.util.Map;

@ApplicationScoped
@Path("/notifications/preferences")
public class NotificationPreferenceResource {

    private final NotificationPreferenceStore store;
    private final CurrentPrincipal            principal;
    private final PreferenceValidator         validator;

    @Inject
    public NotificationPreferenceResource(NotificationPreferenceStore store,
                                          CurrentPrincipal principal,
                                          PreferenceValidator validator) {
        this.store     = store;
        this.principal = principal;
        this.validator = validator;
    }

    @GET
    public NotificationPreferences get() {
        return store.get(principal.actorId(), principal.tenancyId())
                    .orElseGet(() -> new NotificationPreferences(
                            principal.actorId(),
                            principal.tenancyId(),
                            Map.of(),
                            null,
                            Instant.EPOCH
                    ));
    }

    @PUT
    public NotificationPreferences update(NotificationPreferenceUpdate update) {
        var existing = store.get(principal.actorId(), principal.tenancyId()).orElse(null);
        try {
            validator.validate(update, existing);
        } catch (IllegalArgumentException e) {
            throw new jakarta.ws.rs.BadRequestException(e.getMessage());
        }
        return store.update(principal.actorId(), principal.tenancyId(), update);
    }
}
