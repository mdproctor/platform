package io.casehub.platform.testing.spring;

import io.casehub.platform.api.identity.TenancyConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SpringFixedCurrentPrincipalTest {

    private SpringFixedCurrentPrincipal principal;

    @BeforeEach
    void setUp() {
        principal = new SpringFixedCurrentPrincipal();
    }

    @Test
    void defaultsMatchMockCurrentPrincipal() {
        assertThat(principal.actorId()).isEqualTo("system");
        assertThat(principal.groups()).isEmpty();
        assertThat(principal.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(principal.isCrossTenantAdmin()).isFalse();
    }

    @Test
    void mutableSettersWork() {
        principal.setActorId("actor-1");
        principal.setTenancyId("tenant-1");
        principal.setGroups(Set.of("admins"));
        principal.setCrossTenantAdmin(true);

        assertThat(principal.actorId()).isEqualTo("actor-1");
        assertThat(principal.tenancyId()).isEqualTo("tenant-1");
        assertThat(principal.groups()).containsExactly("admins");
        assertThat(principal.isCrossTenantAdmin()).isTrue();
    }

    @Test
    void addGroupAccumulates() {
        principal.addGroup("editors");
        principal.addGroup("viewers");
        assertThat(principal.groups()).containsExactlyInAnyOrder("editors", "viewers");
    }

    @Test
    void resetRestoresDefaults() {
        principal.setActorId("changed");
        principal.setTenancyId("changed-tenant");
        principal.setGroups(Set.of("some-group"));
        principal.setCrossTenantAdmin(true);

        principal.reset();

        assertThat(principal.actorId()).isEqualTo("system");
        assertThat(principal.groups()).isEmpty();
        assertThat(principal.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);
        assertThat(principal.isCrossTenantAdmin()).isFalse();
    }

    @Test
    void groupsReturnedAsUnmodifiableCopy() {
        principal.addGroup("test");
        Set<String> groups = principal.groups();
        assertThat(groups).isUnmodifiable();
    }
}
