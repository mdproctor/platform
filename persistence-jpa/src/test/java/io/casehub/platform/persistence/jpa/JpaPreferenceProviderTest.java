package io.casehub.platform.persistence.jpa;

import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.platform.api.preferences.SingleValuePreference;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class JpaPreferenceProviderTest {

    private static final String TENANT = io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID;

    @Inject PreferenceProvider preferenceProvider;

    // Minimal test preference type
    record Count(int value) implements SingleValuePreference {
        static final PreferenceKey<Count> KEY = new PreferenceKey<>(
                "test", "count", new Count(0), s -> new Count(Integer.parseInt(s)));
    }

    @BeforeEach
    @Transactional
    void clear() {
        PreferenceEntry.deleteAll();
    }

    @Test
    @Transactional
    void resolve_returns_value_stored_for_exact_scope() {
        insert("casehubio/devtown", "test", "count", "", "42");

        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(42, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    @Transactional
    void resolve_inherits_value_from_parent_scope() {
        insert("casehubio", "test", "count", "", "10");

        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown", "pr-review")));

        assertEquals(10, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    @Transactional
    void resolve_child_scope_overrides_parent() {
        insert("casehubio", "test", "count", "", "10");
        insert("casehubio/devtown", "test", "count", "", "99");

        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(99, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    @Transactional
    void resolve_deeper_child_overrides_grandparent() {
        insert("casehubio", "test", "count", "", "10");
        insert("casehubio/devtown/pr-review", "test", "count", "", "77");

        Preferences prefs = preferenceProvider.resolve(
                SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown", "pr-review")));

        assertEquals(77, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_returns_key_default_when_no_rows_match() {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(0, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    @Transactional
    void resolve_ignores_sibling_scope() {
        insert("casehubio/other", "test", "count", "", "99");

        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(0, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    @Transactional
    void resolve_root_scope_directly() {
        insert("", "test", "count", "", "7");

        Preferences prefs = preferenceProvider.resolve(SettingsScope.root(TENANT));

        assertEquals(7, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    @Transactional
    void resolve_root_scope_is_fallback_for_single_segment_scope() {
        insert("", "test", "count", "", "5");

        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio")));

        assertEquals(5, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    @Transactional
    void resolve_root_scope_is_fallback_for_multi_segment_scope() {
        insert("", "test", "count", "", "3");

        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(3, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    @Transactional
    void resolve_explicit_scope_overrides_root() {
        insert("", "test", "count", "", "1");
        insert("casehubio", "test", "count", "", "99");

        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio")));

        assertEquals(99, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    @Transactional
    void resolve_three_level_chain_with_root_base() {
        insert("", "test", "count", "", "1");
        insert("casehubio", "test", "count", "", "10");
        insert("casehubio/devtown", "test", "count", "", "100");

        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(100, prefs.getOrDefault(Count.KEY).value());
    }

    private void insert(String scope, String namespace, String name, String subKey, String value) {
        PreferenceEntry e = new PreferenceEntry();
        e.tenancyId = TENANT;
        e.scope     = scope;
        e.namespace = namespace;
        e.name      = name;
        e.subKey    = subKey;
        e.value     = value;
        e.persist();
    }
}
