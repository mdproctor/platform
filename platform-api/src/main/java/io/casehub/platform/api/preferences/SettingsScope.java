package io.casehub.platform.api.preferences;

import io.casehub.platform.api.path.Path;
import java.time.Instant;
import java.util.Objects;

public record SettingsScope(String tenancyId, Path scope, Instant effectiveAt) {

    public SettingsScope {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
    }

    public static SettingsScope of(String tenancyId, Path scope) {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        return new SettingsScope(tenancyId, scope, Instant.now());
    }

    public static SettingsScope root(String tenancyId) {
        return new SettingsScope(tenancyId, Path.root(), Instant.now());
    }
}
