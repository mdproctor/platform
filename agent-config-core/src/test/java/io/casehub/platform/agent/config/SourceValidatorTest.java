package io.casehub.platform.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class SourceValidatorTest {

    @Test
    void validBearerAuth() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10,
                new SourceAuth("bearer", "env:TOKEN", null), null);
        assertThat(SourceValidator.validate(source)).isEmpty();
    }

    @Test
    void validHeaderAuth() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10,
                new SourceAuth("header", "env:KEY", "X-Api-Key"), null);
        assertThat(SourceValidator.validate(source)).isEmpty();
    }

    @Test
    void validBasicAuth() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10,
                new SourceAuth("basic", "env:CREDS", null), null);
        assertThat(SourceValidator.validate(source)).isEmpty();
    }

    @Test
    void unknownAuthType() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10,
                new SourceAuth("oauth2", "env:TOKEN", null), null);
        assertThat(SourceValidator.validate(source))
                .anyMatch(e -> e.contains("Unknown auth type"));
    }

    @Test
    void headerAuthMissingHeaderName() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10,
                new SourceAuth("header", "env:KEY", null), null);
        assertThat(SourceValidator.validate(source))
                .anyMatch(e -> e.contains("header-name required"));
    }

    @Test
    void invalidHeaderNameCharacters() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10,
                new SourceAuth("header", "env:KEY", "Bad Header"), null);
        assertThat(SourceValidator.validate(source))
                .anyMatch(e -> e.contains("Invalid header name"));
    }

    @Test
    void invalidCredentialRef() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10,
                new SourceAuth("bearer", "raw-value-no-prefix", null), null);
        assertThat(SourceValidator.validate(source))
                .anyMatch(e -> e.contains("Invalid credential reference"));
    }

    @Test
    void validDigestOnly() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10, null,
                new SourceIntegrity("sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", null, null));
        assertThat(SourceValidator.validate(source)).isEmpty();
    }

    @Test
    void signatureWithoutSigner() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10, null,
                new SourceIntegrity("sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", "dGVzdA", null));
        assertThat(SourceValidator.validate(source))
                .anyMatch(e -> e.contains("signer required"));
    }

    @Test
    void signerWithoutSignature() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10, null,
                new SourceIntegrity("sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", null, "did:web:x.example"));
        assertThat(SourceValidator.validate(source))
                .anyMatch(e -> e.contains("signature required"));
    }

    @Test
    void unknownDigestAlgorithm() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10, null,
                new SourceIntegrity("md5:abcdef", null, null));
        assertThat(SourceValidator.validate(source))
                .anyMatch(e -> e.contains("Unknown digest algorithm"));
    }

    @Test
    void malformedDigestHex() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10, null,
                new SourceIntegrity("sha256:tooshort", null, null));
        assertThat(SourceValidator.validate(source))
                .anyMatch(e -> e.contains("Malformed digest value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not!valid", "has spaces", "has+plus"})
    void invalidBase64urlSignature(String sig) {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10, null,
                new SourceIntegrity("sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                        sig, "did:web:x.example"));
        assertThat(SourceValidator.validate(source))
                .anyMatch(e -> e.contains("Invalid signature encoding"));
    }

    @Test
    void noAuthNoIntegrityIsValid() {
        var source = new SourceDeclaration("https://x.com/m.yaml", 10, null, null);
        assertThat(SourceValidator.validate(source)).isEmpty();
    }
}
