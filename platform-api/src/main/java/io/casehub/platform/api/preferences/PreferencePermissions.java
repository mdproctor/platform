package io.casehub.platform.api.preferences;

import io.casehub.platform.api.identity.CurrentPrincipal;

public final class PreferencePermissions {
    private PreferencePermissions() {}

    public static void assertTenant(String tenancyId, CurrentPrincipal principal) {
        if (!principal.tenancyId().equals(tenancyId))
            throw new SecurityException(
                "Tenant ID mismatch: claimed=" + tenancyId
                + ", authenticated=" + principal.tenancyId());
    }
}
