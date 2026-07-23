package io.casehub.platform.persistence.mongodb;

import io.casehub.platform.api.preferences.MultiValuePreference;
import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.platform.api.preferences.SingleValuePreference;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class MongoPreferenceProviderTest {

    private static final String TENANT = io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID;

    @Inject PreferenceProvider preferenceProvider;

    record Count(int value) implements SingleValuePreference {
        static final PreferenceKey<Count> KEY = new PreferenceKey<>(
                "test", "count", new Count(0), s -> new Count(Integer.parseInt(s)));
    }

    record Threshold(String role, int value) implements MultiValuePreference {
        static final PreferenceKey<Threshold> KEY = new PreferenceKey<>(
                "test", "threshold", new Threshold("default", 0),
                s -> new Threshold("parsed", Integer.parseInt(s)));
    }

    @BeforeEach
    void clear() {
        MongoPreferenceDocument.deleteAll();
    }

    @Test
    void resolve_returns_value_stored_for_exact_scope() {
        insert("casehubio/devtown", "test", "count", "", "42");

        final Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(42, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_inherits_value_from_parent_scope() {
        insert("casehubio", "test", "count", "", "10");

        final Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown", "pr-review")));

        assertEquals(10, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_child_scope_overrides_parent() {
        insert("casehubio", "test", "count", "", "10");
        insert("casehubio/devtown", "test", "count", "", "99");

        final Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(99, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_deeper_child_overrides_grandparent() {
        insert("casehubio", "test", "count", "", "10");
        insert("casehubio/devtown/pr-review", "test", "count", "", "77");

        final Preferences prefs = preferenceProvider.resolve(
                SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown", "pr-review")));

        assertEquals(77, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_returns_key_default_when_no_rows_match() {
        final Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(0, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_ignores_sibling_scope() {
        insert("casehubio/other", "test", "count", "", "99");

        final Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(0, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_multi_value_preference_via_subkey() {
        insert("casehubio/devtown", "test", "threshold", "senior", "100");
        insert("casehubio/devtown", "test", "threshold", "junior", "10");

        final Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(100, prefs.get(Threshold.KEY, "senior").value());
        assertEquals(10,  prefs.get(Threshold.KEY, "junior").value());
        assertNull(prefs.get(Threshold.KEY, "absent"));
    }

    @Test
    void resolve_root_scope_directly() {
        insert("", "test", "count", "", "7");

        final Preferences prefs = preferenceProvider.resolve(SettingsScope.root(TENANT));

        assertEquals(7, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_root_scope_is_fallback_for_single_segment_scope() {
        insert("", "test", "count", "", "5");

        final Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio")));

        assertEquals(5, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_root_scope_is_fallback_for_multi_segment_scope() {
        insert("", "test", "count", "", "3");

        final Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(3, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_explicit_scope_overrides_root() {
        insert("", "test", "count", "", "1");
        insert("casehubio", "test", "count", "", "99");

        final Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio")));

        assertEquals(99, prefs.getOrDefault(Count.KEY).value());
    }

    @Test
    void resolve_three_level_chain_with_root_base() {
        insert("", "test", "count", "", "1");
        insert("casehubio", "test", "count", "", "10");
        insert("casehubio/devtown", "test", "count", "", "100");

        final Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TENANT, io.casehub.platform.api.path.Path.of("casehubio", "devtown")));

        assertEquals(100, prefs.getOrDefault(Count.KEY).value());
    }

    private void insert(final String scope, final String namespace,
                        final String name, final String subKey, final String value) {
        final MongoPreferenceDocument doc = new MongoPreferenceDocument();
        doc.id        = MongoPreferenceDocument.compoundId(TENANT, scope, namespace, name, subKey);
        doc.tenancyId = TENANT;
        doc.scope     = scope;
        doc.namespace = namespace;
        doc.name      = name;
        doc.subKey    = subKey;
        doc.value     = value;
        doc.persist();
    }
}
