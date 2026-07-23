package io.casehub.platform.api.preferences;

import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferencePermissionsTest {

    @Test
    void assertTenant_passes_when_tenancy_matches() {
        CurrentPrincipal principal = stubPrincipal("tenant-1");
        assertDoesNotThrow(() -> PreferencePermissions.assertTenant("tenant-1", principal));
    }

    @Test
    void assertTenant_throws_when_tenancy_mismatches() {
        CurrentPrincipal principal = stubPrincipal("tenant-1");
        SecurityException ex = assertThrows(SecurityException.class,
                () -> PreferencePermissions.assertTenant("tenant-2", principal));
        assertTrue(ex.getMessage().contains("tenant-2"));
        assertTrue(ex.getMessage().contains("tenant-1"));
    }

    private static CurrentPrincipal stubPrincipal(String tenancyId) {
        return new CurrentPrincipal() {
            @Override
            public String actorId()               {return "test-actor";}

            @Override
            public String tenancyId()             {return tenancyId;}

            @Override
            public java.util.Set<String> groups() {return java.util.Set.of();}

            @Override
            public boolean isCrossTenantAdmin()   {return false;}
        };
    }
}
