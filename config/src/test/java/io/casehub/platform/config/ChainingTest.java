package io.casehub.platform.config;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ChainingTest.TwoFileProfile.class)
class ChainingTest {

    public static class TwoFileProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "casehub.platform.config.files",
                "classpath:test-prefs-a.yaml,classpath:test-prefs-b.yaml"
            );
        }
    }

    @Inject
    PreferenceProvider provider;

    @Test
    void later_file_wins_for_same_key_and_scope() {
        // test-prefs-a.yaml: casehubio/devtown → humanApprovalThreshold=500
        // test-prefs-b.yaml: casehubio/devtown → humanApprovalThreshold=750
        // b loaded after a → 750 wins
        Preferences prefs = provider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.of("casehubio", "devtown")));
        assertEquals("750", prefs.asMap().get("devtown.humanApprovalThreshold"));
    }

    @Test
    void earlier_file_keys_not_in_later_file_are_preserved() {
        // test-prefs-b.yaml only sets humanApprovalThreshold — securityReviewRequired comes from a
        Preferences prefs = provider.resolve(SettingsScope.of(TenancyConstants.DEFAULT_TENANT_ID, Path.of("casehubio", "devtown")));
        assertEquals("false", prefs.asMap().get("devtown.securityReviewRequired"));
    }
}
