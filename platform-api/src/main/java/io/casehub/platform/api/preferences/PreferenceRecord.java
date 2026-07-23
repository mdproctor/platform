package io.casehub.platform.api.preferences;

import io.casehub.platform.api.path.Path;
import java.util.Objects;

public record PreferenceRecord(String tenancyId, Path scope, String namespace,
                                String name, String subKey, String value) {
    public PreferenceRecord {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(subKey, "subKey must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
