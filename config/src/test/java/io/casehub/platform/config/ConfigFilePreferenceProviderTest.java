package io.casehub.platform.config;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.platform.api.preferences.SingleValuePreference;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ConfigFilePreferenceProviderTest {

    @Inject
    PreferenceProvider provider;

    record Threshold(int value) implements SingleValuePreference {
        static final Threshold DEFAULT = new Threshold(-1);
        static final PreferenceKey<Threshold> KEY =
            new PreferenceKey<>("devtown", "humanApprovalThreshold", DEFAULT,
                s -> new Threshold(Integer.parseInt(s)));
    }

    record SecReview(boolean value) implements SingleValuePreference {
        static final SecReview DEFAULT = new SecReview(false);
        static final PreferenceKey<SecReview> KEY =
            new PreferenceKey<>("devtown", "securityReviewRequired", DEFAULT,
                s -> new SecReview(Boolean.parseBoolean(s)));
    }

    record BaseDefault(String value) implements SingleValuePreference {
        static final BaseDefault DEFAULT = new BaseDefault("none");
        static final PreferenceKey<BaseDefault> KEY =
            new PreferenceKey<>("devtown", "baseDefault", DEFAULT, BaseDefault::new);
    }

    @Test
    void injected_provider_is_ConfigFilePreferenceProvider() {
        assertTrue(provider instanceof ConfigFilePreferenceProvider,
            "Expected ConfigFilePreferenceProvider but got: " + provider.getClass().getSimpleName());
    }

    @Test
    void unscoped_value_resolved_for_any_scope() {
        Preferences prefs = provider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.of("casehubio", "aml", "investigation")));
        assertEquals("base", prefs.asMap().get("devtown.baseDefault"));
    }

    @Test
    void scope_specific_value_returned_for_exact_scope() {
        Preferences prefs = provider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.of("casehubio", "devtown")));
        assertEquals(500, prefs.getOrDefault(Threshold.KEY).value());
    }

    @Test
    void child_scope_overrides_parent() {
        Preferences prefs = provider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.of("casehubio", "devtown", "pr-review")));
        assertEquals(100, prefs.getOrDefault(Threshold.KEY).value());
        assertTrue(prefs.getOrDefault(SecReview.KEY).value());
    }

    @Test
    void parent_value_inherited_when_child_does_not_override() {
        // casehubio/devtown/code-review is not in YAML — inherits from casehubio/devtown
        Preferences prefs = provider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.of("casehubio", "devtown", "code-review")));
        assertEquals(500, prefs.getOrDefault(Threshold.KEY).value());
        assertFalse(prefs.getOrDefault(SecReview.KEY).value()); // inherited false from devtown
    }

    @Test
    void unset_key_returns_key_default() {
        Preferences prefs = provider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.of("casehubio", "aml", "investigation")));
        assertEquals(-1, prefs.getOrDefault(Threshold.KEY).value());
    }

    @Test
    void asMap_returns_strings_for_expression_evaluator_injection() {
        Preferences prefs = provider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.of("casehubio", "devtown")));
        assertEquals("500", prefs.asMap().get("devtown.humanApprovalThreshold"));
    }

    @Test
    void smallrye_config_key_appears_in_resolved_preferences() {
        // "test.smOverride" is NOT in any YAML file — it comes from SmallRye Config
        // (application.properties: casehub.platform.preferences.defaults.test.smOverride=from-smallrye)
        Preferences prefs = provider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.of("casehubio", "devtown")));
        assertEquals("from-smallrye", prefs.asMap().get("test.smOverride"));
    }
}
