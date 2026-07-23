package io.casehub.platform.api.preferences;

import io.casehub.platform.api.path.Path;
import java.util.Objects;

public record PreferenceChanged(String tenancyId, Path scope, String namespace) {
    public PreferenceChanged {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
    }
}
