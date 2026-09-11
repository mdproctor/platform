package io.casehub.platform.display;

import io.casehub.platform.api.display.DisplayTermResolver;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NoOpDisplayTermResolverTest {

    private final DisplayTermResolver resolver = new NoOpDisplayTermResolver();

    @Test
    void resolveLabel_returnsRawValue() {
        assertThat(resolver.resolveLabel("D", "urn:disc")).isEqualTo("D");
    }

    @Test
    void resolveLabel_withNullValue_returnsNull() {
        assertThat(resolver.resolveLabel(null, "urn:disc")).isNull();
    }

    @Test
    void mapTerm_returnsEmpty() {
        assertThat(resolver.mapTerm("D", "urn:disc", "urn:bigfive")).isEmpty();
    }

    @Test
    void mapTerm_withContext_returnsEmpty() {
        assertThat(resolver.mapTerm("D", "urn:disc", "urn:bigfive", "autonomy")).isEmpty();
    }
}
