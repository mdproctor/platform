package io.casehub.platform.mock;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class MockCurrentPrincipal implements CurrentPrincipal {

    private final String actorId;
    private final List<String> groups;
    private final String tenancyId;
    private final boolean crossTenantAdmin;

    public MockCurrentPrincipal() {
        this("system", List.of(), TenancyConstants.DEFAULT_TENANT_ID, false);
    }

    public MockCurrentPrincipal(String actorId, List<String> groups, String tenancyId, boolean crossTenantAdmin) {
        this.actorId = actorId;
        this.groups = groups != null ? groups : List.of();
        this.tenancyId = tenancyId;
        this.crossTenantAdmin = crossTenantAdmin;
    }

    @Override public String actorId() { return actorId; }
    @Override public Set<String> groups() {
        return groups.stream().filter(Predicate.not(String::isBlank)).collect(Collectors.toUnmodifiableSet());
    }
    @Override public String tenancyId() { return tenancyId; }
    @Override public boolean isCrossTenantAdmin() { return crossTenantAdmin; }
}
