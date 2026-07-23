package io.casehub.platform.mock;

import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceQuery;
import io.casehub.platform.api.preferences.PreferenceRecord;
import io.casehub.platform.api.preferences.PreferenceStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
@DefaultBean
public class NoOpPreferenceStore implements PreferenceStore {

    @Override
    public void set(String tenancyId, Path scope, String namespace, String name, String subKey, String value) {}

    @Override
    public void delete(String tenancyId, Path scope, String namespace, String name, String subKey) {}

    @Override
    public List<PreferenceRecord> list(PreferenceQuery query) {
        return List.of();
    }

    @Override
    public void deleteAll(String tenancyId, Path scope, String namespace) {}
}
