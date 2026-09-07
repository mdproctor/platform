package io.casehub.platform.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InterpolationTest {

    @Test
    void interpolate_replaces_system_property() {
        System.setProperty("TEST_CONFIG_THRESHOLD", "999");
        try {
            assertEquals("999", ConfigFilePreferenceProvider.interpolate("${TEST_CONFIG_THRESHOLD}"));
        } finally {
            System.clearProperty("TEST_CONFIG_THRESHOLD");
        }
    }

    @Test
    void interpolate_leaves_unresolved_vars_as_is() {
        assertEquals("${NONEXISTENT_VAR_CASEHUB_XYZ}",
            ConfigFilePreferenceProvider.interpolate("${NONEXISTENT_VAR_CASEHUB_XYZ}"));
    }

    @Test
    void interpolate_handles_null() {
        assertNull(ConfigFilePreferenceProvider.interpolate(null));
    }

    @Test
    void interpolate_handles_string_without_vars() {
        assertEquals("plain", ConfigFilePreferenceProvider.interpolate("plain"));
    }

    @Test
    void interpolate_handles_multiple_vars() {
        System.setProperty("A_KEY", "hello");
        System.setProperty("B_KEY", "world");
        try {
            assertEquals("hello-world", ConfigFilePreferenceProvider.interpolate("${A_KEY}-${B_KEY}"));
        } finally {
            System.clearProperty("A_KEY");
            System.clearProperty("B_KEY");
        }
    }
}
