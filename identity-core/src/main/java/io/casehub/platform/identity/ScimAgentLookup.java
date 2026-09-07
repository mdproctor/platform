package io.casehub.platform.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

public class ScimAgentLookup extends AbstractCachingIdentityProvider<ScimAgentResource> {

    private static final Logger LOG = Logger.getLogger(ScimAgentLookup.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXTENSION_KEY =
            "urn:ietf:params:scim:schemas:extension:casehub:2.0:Agent";

    private final String scimEndpoint;
    private final String authToken;
    private final int timeoutMs;
    private final boolean requireHttps;
    private final HttpClient httpClient;

    public ScimAgentLookup(final String endpoint, final String authToken,
                            final int timeoutMs, final Duration cacheTtl,
                            final boolean requireHttps) {
        super(cacheTtl);
        this.scimEndpoint = endpoint;
        this.authToken = authToken;
        this.timeoutMs = timeoutMs;
        this.requireHttps = requireHttps;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs)).build();
    }

    public static ScimAgentLookup unconfigured() {
        return new ScimAgentLookup("", "", 5000, Duration.ofMinutes(5), true);
    }

    public boolean isConfigured() {
        return scimEndpoint != null && !scimEndpoint.isBlank();
    }

    public void validate() {
        if (!isConfigured()) {
            throw new IllegalArgumentException(
                    "casehub.identity.scim.endpoint must be configured");
        }
        if (requireHttps && !scimEndpoint.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "casehub.identity.scim.endpoint must use HTTPS, got: " + scimEndpoint);
        }
        if (authToken == null || authToken.isBlank()) {
            throw new IllegalArgumentException(
                    "casehub.identity.scim.auth-token must not be blank when endpoint is configured");
        }
    }

    @Override
    protected Optional<ScimAgentResource> loadContext(final String actorId) {
        if (!isConfigured()) return Optional.empty();
        validate();

        final String encodedActorId = URLEncoder.encode(actorId, StandardCharsets.UTF_8)
                .replace("+", "%20");
        final String url = scimEndpoint + "/scim/v2/Agents?filter=externalId%20eq%20%22"
                + encodedActorId + "%22";

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + authToken)
                .header("Accept", "application/json")
                .GET().build();

        final HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final Exception e) {
            LOG.warnf("SCIM request failed for actorId %s: %s", actorId, e.getMessage());
            throw new IllegalStateException("SCIM request failed for actorId: " + actorId, e);
        }

        return switch (response.statusCode()) {
            case 200 -> parseResponse(actorId, response.body());
            case 401 -> {
                LOG.warnf("SCIM authentication failed (HTTP 401) for actorId: %s", actorId);
                throw new IllegalStateException(
                        "SCIM authentication failed (HTTP 401) for actorId: " + actorId);
            }
            case 404 -> {
                LOG.warnf("SCIM endpoint returned 404 — possible misconfiguration: %s", scimEndpoint);
                throw new IllegalStateException(
                        "SCIM endpoint returned 404 — possible misconfiguration: " + scimEndpoint);
            }
            default -> {
                LOG.warnf("SCIM returned unexpected status %d for actorId %s",
                        response.statusCode(), actorId);
                throw new IllegalStateException(
                        "SCIM returned unexpected status " + response.statusCode()
                        + " for actorId: " + actorId);
            }
        };
    }

    private Optional<ScimAgentResource> parseResponse(final String actorId, final String body) {
        try {
            final JsonNode root = MAPPER.readTree(body);
            final int totalResults = root.path("totalResults").asInt(0);
            if (totalResults == 0) return Optional.empty();
            if (totalResults > 1) {
                LOG.warnf("SCIM returned %d results for externalId %s — using first",
                        totalResults, actorId);
            }
            final JsonNode resource = root.path("Resources").get(0);
            final JsonNode extension = resource.path(EXTENSION_KEY);
            final String did = extension.path("did").asText(null);
            if (did == null || did.isBlank()) {
                throw new IllegalStateException(
                        "SCIM resource for actorId " + actorId + " is missing required 'did' field");
            }

            final List<byte[]> certs = new ArrayList<>();
            final JsonNode certArray = resource.path("x509Certificates");
            if (certArray.isArray()) {
                for (final JsonNode certNode : certArray) {
                    final String value = certNode.path("value").asText(null);
                    if (value != null && !value.isBlank()) {
                        try {
                            certs.add(Base64.getDecoder().decode(value));
                        } catch (final IllegalArgumentException e) {
                            LOG.warnf("Invalid Base64 certificate data for actorId %s: %s",
                                    actorId, e.getMessage());
                        }
                    }
                }
            }

            return Optional.of(new ScimAgentResource(did, certs));
        } catch (final IllegalStateException e) {
            throw e;
        } catch (final Exception e) {
            LOG.warnf("Failed to parse SCIM response for actorId %s: %s", actorId, e.getMessage());
            throw new IllegalStateException("Failed to parse SCIM response for actorId: " + actorId, e);
        }
    }
}
