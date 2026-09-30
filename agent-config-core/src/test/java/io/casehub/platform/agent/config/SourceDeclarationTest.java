package io.casehub.platform.agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SourceDeclarationTest {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void deserializesAuthBearerSource() throws Exception {
        var yaml = """
                uri: https://corp.example/models.yaml
                priority: 40
                auth:
                  type: bearer
                  credential: env:CORP_TOKEN
                """;
        var source = mapper.readValue(yaml, SourceDeclaration.class);
        assertThat(source.uri()).isEqualTo("https://corp.example/models.yaml");
        assertThat(source.priority()).isEqualTo(40);
        assertThat(source.auth()).isNotNull();
        assertThat(source.auth().type()).isEqualTo("bearer");
        assertThat(source.auth().credential()).isEqualTo("env:CORP_TOKEN");
        assertThat(source.auth().headerName()).isNull();
        assertThat(source.integrity()).isNull();
    }

    @Test
    void deserializesAuthHeaderSource() throws Exception {
        var yaml = """
                uri: https://partner.example/models.yaml
                priority: 45
                auth:
                  type: header
                  credential: env:PARTNER_KEY
                  header-name: X-Api-Key
                """;
        var source = mapper.readValue(yaml, SourceDeclaration.class);
        assertThat(source.auth().type()).isEqualTo("header");
        assertThat(source.auth().headerName()).isEqualTo("X-Api-Key");
    }

    @Test
    void deserializesIntegrityWithDigestOnly() throws Exception {
        var yaml = """
                uri: https://registry.example/models.yaml
                priority: 50
                integrity:
                  digest: sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789
                """;
        var source = mapper.readValue(yaml, SourceDeclaration.class);
        assertThat(source.auth()).isNull();
        assertThat(source.integrity()).isNotNull();
        assertThat(source.integrity().digest()).startsWith("sha256:");
        assertThat(source.integrity().signature()).isNull();
        assertThat(source.integrity().signer()).isNull();
    }

    @Test
    void deserializesIntegrityWithSignature() throws Exception {
        var yaml = """
                uri: https://partner.example/models.yaml
                priority: 45
                integrity:
                  digest: sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789
                  signature: dGVzdA
                  signer: did:web:partner.example
                """;
        var source = mapper.readValue(yaml, SourceDeclaration.class);
        assertThat(source.integrity().signature()).isEqualTo("dGVzdA");
        assertThat(source.integrity().signer()).isEqualTo("did:web:partner.example");
    }

    @Test
    void deserializesLegacySourceWithoutAuthOrIntegrity() throws Exception {
        var yaml = """
                uri: https://internal.corp/models.yaml
                priority: 35
                """;
        var source = mapper.readValue(yaml, SourceDeclaration.class);
        assertThat(source.uri()).isEqualTo("https://internal.corp/models.yaml");
        assertThat(source.priority()).isEqualTo(35);
        assertThat(source.auth()).isNull();
        assertThat(source.integrity()).isNull();
    }
}
