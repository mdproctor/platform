package io.casehub.platform.agent.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestLoaderFetchTest {

    private static final String VALID_YAML = """
            providers:
              - vendor: anthropic
                credential: env:ANTHROPIC_API_KEY
            defaults:
              backend: claude
            """;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void fetchesManifestWithoutAuth() {
        server.createContext("/models.yaml", exchange -> {
            var bytes = VALID_YAML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/yaml");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var loader = new ManifestLoader(null, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/models.yaml", 10, null, null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNotNull();
        assertThat(result.defaults().backend()).isEqualTo("claude");
    }

    @Test
    void sendsBearerAuthHeader() {
        var capturedAuth = new String[1];
        server.createContext("/models.yaml", exchange -> {
            capturedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
            var bytes = VALID_YAML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/yaml");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var credResolver = new ManifestCredentialResolver(ref -> java.util.Map.of("token", "test-token-123"));
        var loader = new ManifestLoader(credResolver, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/models.yaml", 10,
                new SourceAuth("bearer", "ref:test-cred", null), null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNotNull();
        assertThat(capturedAuth[0]).isEqualTo("Bearer test-token-123");
    }

    @Test
    void sendsCustomHeader() {
        var capturedHeader = new String[1];
        server.createContext("/models.yaml", exchange -> {
            capturedHeader[0] = exchange.getRequestHeaders().getFirst("X-Api-Key");
            var bytes = VALID_YAML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/yaml");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var credResolver = new ManifestCredentialResolver(ref -> java.util.Map.of("key", "my-api-key"));
        var loader = new ManifestLoader(credResolver, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/models.yaml", 10,
                new SourceAuth("header", "ref:test-cred", "X-Api-Key"), null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNotNull();
        assertThat(capturedHeader[0]).isEqualTo("my-api-key");
    }

    @Test
    void rejectsRedirect() {
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", baseUrl + "/models.yaml");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        var loader = new ManifestLoader(null, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/redirect", 10, null, null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNull();
    }

    @Test
    void rejectsWrongContentType() {
        server.createContext("/login.html", exchange -> {
            var bytes = "<html>Login</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var loader = new ManifestLoader(null, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/login.html", 10, null, null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNull();
    }

    @Test
    void allowsMissingContentType() {
        server.createContext("/models.yaml", exchange -> {
            var bytes = VALID_YAML.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var loader = new ManifestLoader(null, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/models.yaml", 10, null, null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNotNull();
    }

    @Test
    void acceptsContentTypeWithParameters() {
        server.createContext("/models.yaml", exchange -> {
            var bytes = VALID_YAML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/yaml; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var loader = new ManifestLoader(null, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/models.yaml", 10, null, null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNotNull();
    }

    @Test
    void rejectsOversizedResponse() {
        server.createContext("/huge.yaml", exchange -> {
            var bytes = new byte[1_048_577]; // 1 MB + 1
            java.util.Arrays.fill(bytes, (byte) ' ');
            exchange.getResponseHeaders().set("Content-Type", "application/yaml");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var loader = new ManifestLoader(null, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/huge.yaml", 10, null, null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNull();
    }

    @Test
    void verifyDigestEndToEnd() throws Exception {
        var bytes = VALID_YAML.getBytes(StandardCharsets.UTF_8);
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        server.createContext("/models.yaml", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/yaml");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var loader = new ManifestLoader(null, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/models.yaml", 10, null,
                new SourceIntegrity("sha256:" + hash, null, null));
        var result = loader.fetchRemote(source);

        assertThat(result).isNotNull();
        assertThat(result.defaults().backend()).isEqualTo("claude");
    }

    @Test
    void rejectsDigestMismatchEndToEnd() {
        server.createContext("/models.yaml", exchange -> {
            var bytes = VALID_YAML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/yaml");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var loader = new ManifestLoader(null, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/models.yaml", 10, null,
                new SourceIntegrity("sha256:" + "0".repeat(64), null, null));
        var result = loader.fetchRemote(source);

        assertThat(result).isNull();
    }

    @Test
    void rejectsHttpError() {
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });

        var loader = new ManifestLoader(null, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/error", 10, null, null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNull();
    }

    @Test
    void failsClosedWhenAuthDeclaredButNoResolver() {
        var loader = new ManifestLoader(null, null, new ManifestSecurityConfig(false));
        var source = new SourceDeclaration(baseUrl + "/models.yaml", 10,
                new SourceAuth("bearer", "env:TOKEN", null), null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNull();
    }

    @Test
    void backwardCompatibleWithLegacySource() {
        server.createContext("/models.yaml", exchange -> {
            var bytes = VALID_YAML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/yaml");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        var loader = new ManifestLoader();
        var source = new SourceDeclaration(baseUrl + "/models.yaml", 10, null, null);
        var result = loader.fetchRemote(source);

        assertThat(result).isNotNull();
    }
}
