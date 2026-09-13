package io.casehub.platform.api.model;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelDescriptorTest {

    private ModelDescriptor descriptor(CostTier cost, String authMethod) {
        return new ModelDescriptor(
            "claude-sonnet-5", "claude-sonnet-5", "claude", null, "anthropic", "claude",
            "Claude Sonnet 5", ModelTier.STANDARD,
            Set.of(ModelCapabilities.TEXT, ModelCapabilities.VISION),
            200000, 16384, ModelLocality.CLOUD, cost, authMethod, Map.of());
    }

    @Test
    void requiredFields_throwOnNull() {
        assertThatThrownBy(() -> new ModelDescriptor(
            null, "id", "claude", null, "anthropic", "claude", "name",
            ModelTier.STANDARD, Set.of(), 200000, 16384,
            ModelLocality.CLOUD, null, null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void capabilities_defensiveCopy() {
        var mutable = new java.util.HashSet<>(Set.of("text"));
        var desc = new ModelDescriptor("id", "id", "key", null, "vendor", "family", "name",
            ModelTier.FAST, mutable, 100000, 8192,
            ModelLocality.CLOUD, null, null, null);
        mutable.add("vision");
        assertThat(desc.capabilities()).containsExactly("text");
    }

    @Test
    void properties_defensiveCopy() {
        var mutable = new java.util.HashMap<>(Map.of("k", "v"));
        var desc = new ModelDescriptor("id", "id", "key", null, "vendor", "family", "name",
            ModelTier.FAST, Set.of(), 100000, 8192,
            ModelLocality.CLOUD, null, null, mutable);
        mutable.put("k2", "v2");
        assertThat(desc.properties()).containsOnlyKeys("k");
    }

    @Test
    void nullCapabilities_defaultsToEmptySet() {
        var desc = new ModelDescriptor("id", "id", "key", null, "vendor", "family", "name",
            ModelTier.FAST, null, 100000, 8192,
            ModelLocality.CLOUD, null, null, null);
        assertThat(desc.capabilities()).isEmpty();
    }

    @Test
    void costTier_nullable() {
        var desc = descriptor(null, null);
        assertThat(desc.costTier()).isNull();
    }

    @Test
    void costTier_rankValues() {
        assertThat(CostTier.FREE.rank()).isEqualTo(0);
        assertThat(CostTier.LOW.rank()).isEqualTo(1);
        assertThat(CostTier.MEDIUM.rank()).isEqualTo(2);
        assertThat(CostTier.HIGH.rank()).isEqualTo(3);
        assertThat(CostTier.PREMIUM.rank()).isEqualTo(4);
    }

    @Test
    void authMethod_nullable() {
        var desc = descriptor(CostTier.HIGH, null);
        assertThat(desc.authMethod()).isNull();
    }

    @Test
    void family_separateFromVendor() {
        var desc = descriptor(CostTier.HIGH, "api-key");
        assertThat(desc.vendor()).isEqualTo("anthropic");
        assertThat(desc.family()).isEqualTo("claude");
    }
}
