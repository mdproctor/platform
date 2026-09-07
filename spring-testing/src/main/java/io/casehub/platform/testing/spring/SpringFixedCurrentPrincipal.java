package io.casehub.platform.testing.spring;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;

import java.util.HashSet;
import java.util.Set;

/**
 * Mutable test implementation of {@link CurrentPrincipal} for Spring Boot tests.
 *
 * <p>Mirrors {@code FixedCurrentPrincipal} from casehub-platform-testing (Quarkus)
 * but without CDI annotations. Used with {@link SpringTestConfig} to provide a
 * configurable principal in {@code @SpringBootTest} contexts.
 *
 * <p>Defaults match {@code MockCurrentPrincipal}: actorId="system", empty groups,
 * default tenancyId. Call {@link #reset()} in {@code @BeforeEach} to isolate tests.
 *
 * <p><strong>Not thread-safe</strong> — designed for single-threaded test use only.
 */
public class SpringFixedCurrentPrincipal implements CurrentPrincipal {

    private String actorId = "system";
    private Set<String> groups = new HashSet<>();
    private String tenancyId = TenancyConstants.DEFAULT_TENANT_ID;
    private boolean crossTenantAdmin = false;

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public void setGroups(Set<String> groups) {
        this.groups = new HashSet<>(groups);
    }

    public void addGroup(String group) {
        this.groups.add(group);
    }

    public void setTenancyId(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    public void setCrossTenantAdmin(boolean crossTenantAdmin) {
        this.crossTenantAdmin = crossTenantAdmin;
    }

    public void reset() {
        actorId = "system";
        groups = new HashSet<>();
        tenancyId = TenancyConstants.DEFAULT_TENANT_ID;
        crossTenantAdmin = false;
    }

    @Override public String actorId() { return actorId; }
    @Override public Set<String> groups() { return Set.copyOf(groups); }
    @Override public String tenancyId() { return tenancyId; }
    @Override public boolean isCrossTenantAdmin() { return crossTenantAdmin; }
}
