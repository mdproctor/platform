package io.casehub.yaml.core.playbook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookSchemasTest {

    @Test
    void clientAndServerAreBuiltIn() {
        assertThat(PlaybookSchemas.isBuiltIn("client")).isTrue();
        assertThat(PlaybookSchemas.isBuiltIn("server")).isTrue();
    }

    @Test
    void domainSchemaIsNotBuiltIn() {
        assertThat(PlaybookSchemas.isBuiltIn("clinical-client")).isFalse();
        assertThat(PlaybookSchemas.isBuiltIn("aml-server")).isFalse();
    }

    @Test
    void domainSchemaDetection() {
        assertThat(PlaybookSchemas.isDomainSchema("clinical-client")).isTrue();
        assertThat(PlaybookSchemas.isDomainSchema("aml-server")).isTrue();
    }

    @Test
    void builtInSchemasAreNotDomainSchemas() {
        assertThat(PlaybookSchemas.isDomainSchema("client")).isFalse();
        assertThat(PlaybookSchemas.isDomainSchema("server")).isFalse();
    }

    @Test
    void nullSchemaIsNotDomain() {
        assertThat(PlaybookSchemas.isDomainSchema(null)).isFalse();
    }
}
