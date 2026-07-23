package io.casehub.platform.api.preferences;

import io.casehub.platform.api.path.Path;
import java.util.Objects;

public record PreferenceQuery(String tenancyId, Path scope, String namespace) {
    public PreferenceQuery {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
    }
}
