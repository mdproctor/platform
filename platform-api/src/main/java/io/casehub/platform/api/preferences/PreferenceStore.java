package io.casehub.platform.api.preferences;

import io.casehub.platform.api.path.Path;
import java.util.List;

public interface PreferenceStore {
    void set(String tenancyId, Path scope, String namespace, String name, String subKey, String value);
    void delete(String tenancyId, Path scope, String namespace, String name, String subKey);
    List<PreferenceRecord> list(PreferenceQuery query);
    void deleteAll(String tenancyId, Path scope, String namespace);
}
