package io.casehub.platform.preferences.editor.quarkus;

import io.casehub.platform.preferences.editor.InMemoryPreferenceSchemaRegistry;
import io.casehub.platform.preferences.editor.PreferenceValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class PreferencesEditorBeans {

    @Produces
    @ApplicationScoped
    public InMemoryPreferenceSchemaRegistry inMemoryPreferenceSchemaRegistry() {
        return new InMemoryPreferenceSchemaRegistry();
    }

    @Produces
    @ApplicationScoped
    public PreferenceValidator preferenceValidator() {
        return new PreferenceValidator();
    }
}
