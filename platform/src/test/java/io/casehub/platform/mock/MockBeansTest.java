package io.casehub.platform.mock;

import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.platform.api.endpoints.EndpointQuery;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.GroupMembershipProvider;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.platform.api.preferences.SingleValuePreference;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MockBeansTest {

    @Inject CurrentPrincipal principal;
    @Inject GroupMembershipProvider groupMembership;
    @Inject PreferenceProvider preferenceProvider;
    @Inject AccessControlProvider accessControl;
    @Inject EndpointRegistry endpointRegistry;
    @Inject CredentialResolver credentialResolver;

    @Test
    void currentPrincipal_defaults_to_system() {
        assertEquals("system", principal.actorId());
    }

    @Test
    void currentPrincipal_isSystem_true_by_default() {
        assertTrue(principal.isSystem());
    }

    @Test
    void currentPrincipal_isAuthenticated_true_by_default() {
        assertTrue(principal.isAuthenticated());
    }

    @Test
    void currentPrincipal_groups_empty_by_default() {
        assertTrue(principal.groups().isEmpty());
    }

    @Test
    void groupMembership_always_returns_empty_set() {
        assertTrue(groupMembership.membersOf("any-group").isEmpty());
        assertTrue(groupMembership.membersOf("admin").isEmpty());
    }

    @Test
    void preferenceProvider_resolve_returns_preferences() {
        assertNotNull(preferenceProvider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.parse("acme/backend"))));
    }

    @Test
    void preferenceProvider_asMap_returns_typed_values() {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.parse("acme/backend")));
        assertEquals(42, prefs.asMap().get("test.count"));          // Integer
        assertEquals(Boolean.TRUE, prefs.asMap().get("test.flag")); // Boolean
        assertEquals("hello", prefs.asMap().get("test.label"));     // String
    }

    @Test
    void preferenceProvider_typed_get_returns_parsed_value() {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.parse("acme/backend")));
        record LabelPref(String value) implements io.casehub.platform.api.preferences.SingleValuePreference {}
        PreferenceKey<LabelPref> key = new PreferenceKey<>("test", "label",
                new LabelPref("fallback"),
                LabelPref::new);
        LabelPref result = prefs.get(key);
        assertNotNull(result);
        assertEquals("hello", result.value());
    }

    @Test
    void preferenceProvider_getOrDefault_returns_parsed_value_when_configured() {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.parse("acme/backend")));
        record LabelPref(String value) implements io.casehub.platform.api.preferences.SingleValuePreference {}
        PreferenceKey<LabelPref> key = new PreferenceKey<>("test", "label",
                new LabelPref("fallback"),
                LabelPref::new);
        assertEquals("hello", prefs.getOrDefault(key).value());
    }

    @Test
    void preferenceProvider_getOrDefault_returns_key_default_when_absent() {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.parse("acme/backend")));
        record MissingPref(String value) implements io.casehub.platform.api.preferences.SingleValuePreference {}
        PreferenceKey<MissingPref> key = new PreferenceKey<>("test", "missing",
                new MissingPref("fallback"),
                MissingPref::new);
        assertEquals("fallback", prefs.getOrDefault(key).value());
    }

    @Test
    void preferenceProvider_numeric_config_returns_null_for_typed_get() {
        // parseValue() converts "42" to Integer(42) for asMap().
        // MapPreferences.get(key) only handles String — Integer falls through to null.
        // getOrDefault() then returns key.defaultValue().
        Preferences prefs = preferenceProvider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.parse("acme/backend")));
        assertEquals(42, prefs.asMap().get("test.count"));          // Integer in asMap ✓
        record CountPref(int value) implements SingleValuePreference {}
        PreferenceKey<CountPref> key = new PreferenceKey<>("test", "count",
                new CountPref(-1),
                s -> new CountPref(Integer.parseInt(s)));
        assertNull(prefs.get(key));                                   // null — Integer, not String
        assertEquals(-1, prefs.getOrDefault(key).value());            // falls to defaultValue
    }

    @Test
    void currentPrincipal_tenancyId_defaults_to_default_tenant_id() {
        assertEquals(TenancyConstants.DEFAULT_TENANT_ID, principal.tenancyId());
    }

    @Test
    void currentPrincipal_isCrossTenantAdmin_false_by_default() {
        assertFalse(principal.isCrossTenantAdmin());
    }

    @Test
    void accessControl_noOp_allows_all() {
        assertTrue(accessControl.canAccess("any-actor", "case:any", AclAction.READ).toCompletableFuture().join());
    }

    @Test
    void accessControl_noOp_accessibleResources_returns_empty() {
        assertTrue(accessControl.accessibleResources("any-actor", "case", AclAction.READ).toCompletableFuture().join().isEmpty());
    }

    @Test
    void endpointRegistry_noop_resolve_returns_empty() {
        assertFalse(endpointRegistry.resolve(
                Path.of("any", "path"), TenancyConstants.DEFAULT_TENANT_ID).isPresent());
    }

    @Test
    void endpointRegistry_noop_discover_returns_empty_list() {
        assertTrue(endpointRegistry.discover(
                new EndpointQuery(TenancyConstants.DEFAULT_TENANT_ID, null, null, java.util.Set.of()))
                .isEmpty());
    }

    @Test
    void pathParser_default_separator_is_slash() {
        // PathParserConfigurator sets / as default at startup
        Path p = Path.parse("acme/backend/pr-review");
        assertEquals(List.of("acme", "backend", "pr-review"), p.segments());
    }

    @Test
    void credentialResolver_defaultBean_returns_empty_for_unconfigured_ref() {
        assertTrue(credentialResolver.resolve("unconfigured-ref").isEmpty());
    }
}
