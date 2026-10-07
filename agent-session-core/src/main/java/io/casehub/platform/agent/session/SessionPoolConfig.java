package io.casehub.platform.agent.session;

import io.casehub.platform.agent.config.PoolDeclaration;

import java.time.Duration;

public record SessionPoolConfig(
        String name,
        String backendKey,
        int minActive,
        int maxActive,
        Duration idleTimeout,
        Duration checkoutTimeout
) {
    public SessionPoolConfig {
        if (minActive < 0) throw new IllegalArgumentException("minActive must be >= 0");
        if (maxActive < 1) throw new IllegalArgumentException("maxActive must be >= 1");
        if (maxActive < minActive) throw new IllegalArgumentException("maxActive must be >= minActive");
        if (idleTimeout == null) idleTimeout = Duration.ofSeconds(300);
        if (checkoutTimeout == null) checkoutTimeout = Duration.ofSeconds(30);
    }

    public static SessionPoolConfig fromDeclaration(PoolDeclaration decl) {
        var ext = decl.extensions();
        long idleSecs = ext.containsKey("idle-timeout-seconds")
                ? ((Number) ext.get("idle-timeout-seconds")).longValue() : 300;
        long checkoutSecs = ext.containsKey("checkout-timeout-seconds")
                ? ((Number) ext.get("checkout-timeout-seconds")).longValue() : 30;
        return new SessionPoolConfig(
                decl.name(), decl.backend(),
                decl.minActive(), decl.maxActive(),
                Duration.ofSeconds(idleSecs), Duration.ofSeconds(checkoutSecs));
    }
}
