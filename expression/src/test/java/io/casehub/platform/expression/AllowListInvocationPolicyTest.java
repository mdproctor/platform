package io.casehub.platform.expression;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AllowListInvocationPolicyTest {

    @Test
    void allowsMethodInAllowedPackage() {
        var policy = new AllowListInvocationPolicy(Set.of("io.casehub.trading"));
        assertTrue(policy.isAllowed("io.casehub.trading.OrderService", "place"));
    }

    @Test
    void deniesMethodOutsideAllowedPackage() {
        var policy = new AllowListInvocationPolicy(Set.of("io.casehub.trading"));
        assertFalse(policy.isAllowed("java.lang.Runtime", "exec"));
    }

    @Test
    void allowsSubpackageOfAllowedPrefix() {
        var policy = new AllowListInvocationPolicy(Set.of("io.casehub"));
        assertTrue(policy.isAllowed("io.casehub.trading.sub.DeepService", "call"));
    }

    @Test
    void multipleAllowedPackages() {
        var policy = new AllowListInvocationPolicy(Set.of("io.casehub.trading", "io.casehub.risk"));
        assertTrue(policy.isAllowed("io.casehub.trading.OrderService", "place"));
        assertTrue(policy.isAllowed("io.casehub.risk.RiskEngine", "evaluate"));
        assertFalse(policy.isAllowed("io.casehub.admin.AdminService", "delete"));
    }

    @Test
    void emptyAllowListDeniesEverything() {
        var policy = new AllowListInvocationPolicy(Set.of());
        assertFalse(policy.isAllowed("io.casehub.trading.OrderService", "place"));
    }
}
