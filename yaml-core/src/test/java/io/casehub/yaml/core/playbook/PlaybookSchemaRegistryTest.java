package io.casehub.yaml.core.playbook;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaybookSchemaRegistryTest {

    private final MapPlaybookSchemaRegistry registry = new MapPlaybookSchemaRegistry();

    @Test
    void builtInSchemas_registeredOnConstruction() {
        assertThat(registry.isKnown("client")).isTrue();
        assertThat(registry.isKnown("server")).isTrue();
    }

    @Test
    void clientSchema_hasClientCapabilities() {
        var client = registry.resolveOrThrow("client");

        assertThat(client.hasCapability(PlaybookCapabilities.STEPS)).isTrue();
        assertThat(client.hasCapability(PlaybookCapabilities.ARIA)).isTrue();
        assertThat(client.hasCapability(PlaybookCapabilities.CHAPTERS)).isTrue();
        assertThat(client.hasCapability(PlaybookCapabilities.SECTIONS)).isTrue();
        assertThat(client.hasCapability(PlaybookCapabilities.SPOTLIGHT)).isTrue();
        assertThat(client.isBuiltIn()).isTrue();
    }

    @Test
    void serverSchema_hasServerCapabilities() {
        var server = registry.resolveOrThrow("server");

        assertThat(server.hasCapability(PlaybookCapabilities.STEPS)).isTrue();
        assertThat(server.hasCapability(PlaybookCapabilities.ORCHESTRATION)).isTrue();
        assertThat(server.hasCapability(PlaybookCapabilities.CORRELATION)).isTrue();
        assertThat(server.hasCapability(PlaybookCapabilities.MCP_INVOKE)).isTrue();
        assertThat(server.isBuiltIn()).isTrue();
    }

    @Test
    void clientSchema_doesNotHaveServerCapabilities() {
        var client = registry.resolveOrThrow("client");
        assertThat(client.hasCapability(PlaybookCapabilities.ORCHESTRATION)).isFalse();
        assertThat(client.hasCapability(PlaybookCapabilities.MCP_INVOKE)).isFalse();
    }

    @Test
    void serverSchema_doesNotHaveClientCapabilities() {
        var server = registry.resolveOrThrow("server");
        assertThat(server.hasCapability(PlaybookCapabilities.ARIA)).isFalse();
        assertThat(server.hasCapability(PlaybookCapabilities.CHAPTERS)).isFalse();
    }

    @Test
    void registerDomainSchema_extends_builtIn() {
        registry.register(PlaybookSchemaDescriptor.domain(
                "clinical-server", "server",
                Set.of("clinical-trial", "patient-consent")));

        var clinical = registry.resolveOrThrow("clinical-server");
        assertThat(clinical.isBuiltIn()).isFalse();
        assertThat(clinical.baseSchema()).isEqualTo("server");
        assertThat(clinical.hasCapability("clinical-trial")).isTrue();
    }

    @Test
    void unknownSchema_throws() {
        assertThatThrownBy(() -> registry.resolveOrThrow("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void all_returnsAllRegistered() {
        assertThat(registry.all()).hasSize(2);

        registry.register(PlaybookSchemaDescriptor.domain(
                "aml-server", "server", Set.of()));

        assertThat(registry.all()).hasSize(3);
    }

    @Test
    void resolveEffective_withFrontMatter() {
        var fm = new PlaybookFrontMatter("1.0", "server");
        var desc = registry.resolveEffective(fm);
        assertThat(desc).isNotNull();
        assertThat(desc.name()).isEqualTo("server");
    }

    @Test
    void resolveEffective_withNullFrontMatter_returnsNull() {
        assertThat(registry.resolveEffective(null)).isNull();
    }

    @Test
    void effectiveCapabilities_builtIn_returnsOwnCapabilities() {
        var caps = registry.effectiveCapabilities("server");
        assertThat(caps).contains(
                PlaybookCapabilities.STEPS,
                PlaybookCapabilities.ORCHESTRATION,
                PlaybookCapabilities.CORRELATION);
        assertThat(caps).doesNotContain(PlaybookCapabilities.ARIA);
    }

    @Test
    void effectiveCapabilities_domainSchema_includesBaseCapabilities() {
        registry.register(PlaybookSchemaDescriptor.domain(
                "clinical-server", "server", Set.of("clinical-trial")));

        var caps = registry.effectiveCapabilities("clinical-server");
        assertThat(caps).contains("clinical-trial");
        assertThat(caps).contains(PlaybookCapabilities.STEPS);
        assertThat(caps).contains(PlaybookCapabilities.ORCHESTRATION);
    }

    @Test
    void effectiveCapabilities_multiLevel_resolves() {
        registry.register(PlaybookSchemaDescriptor.domain(
                "clinical-server", "server", Set.of("clinical-trial")));
        registry.register(PlaybookSchemaDescriptor.domain(
                "nhs-clinical-server", "clinical-server", Set.of("nhs-audit")));

        var caps = registry.effectiveCapabilities("nhs-clinical-server");
        assertThat(caps).contains("nhs-audit", "clinical-trial",
                                  PlaybookCapabilities.STEPS, PlaybookCapabilities.ORCHESTRATION);
    }

    @Test
    void effectiveCapabilities_unknownSchema_throws() {
        assertThatThrownBy(() -> registry.effectiveCapabilities("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effectiveCapabilities_circularInheritance_throws() {
        registry.register(new PlaybookSchemaDescriptor("alpha", "beta", Set.of()));
        registry.register(new PlaybookSchemaDescriptor("beta", "alpha", Set.of()));

        assertThatThrownBy(() -> registry.effectiveCapabilities("alpha"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Circular");
    }
}
