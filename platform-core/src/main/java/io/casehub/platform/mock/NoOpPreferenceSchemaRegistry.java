package io.casehub.platform.mock;

import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;
import io.casehub.platform.api.preferences.PreferenceSchemaRegistry;

import java.util.Optional;
import java.util.Set;

public class NoOpPreferenceSchemaRegistry implements PreferenceSchemaRegistry {
    @Override public void register(PreferenceSchemaDescriptor descriptor) {}
    @Override public Optional<PreferenceSchemaDescriptor> resolve(String qualifiedName) { return Optional.empty(); }
    @Override public Set<PreferenceSchemaDescriptor> discover() { return Set.of(); }
}
