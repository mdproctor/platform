package io.casehub.platform.llm.config.vertex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import io.casehub.platform.api.model.ModelCapabilities;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelTier;
import io.casehub.platform.llm.config.ValidationResult;
import io.casehub.platform.llm.config.VendorClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@ApplicationScoped
public class VertexClient implements VendorClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Function<ModelQuery, List<ModelDescriptor>> seedLookup;

    @Inject
    VertexClient(ModelRegistry registry) {
        this.seedLookup = registry::query;
    }

    VertexClient(Function<ModelQuery, List<ModelDescriptor>> seedLookup) {
        this.seedLookup = seedLookup;
    }

    @Override public String vendorKey() { return "vertex"; }
    @Override public String backendKey() { return "claude"; }
    @Override public String displayName() { return "Vertex AI (Anthropic)"; }
    @Override public String authMethod() { return "gcp-adc"; }
    @Override public List<String> requiredFields() { return List.of("project-id", "region"); }

    @Override
    public ValidationResult listModels(Map<String, String> credentials) {
        String projectId = credentials.get("project-id");
        String region = credentials.getOrDefault("region", "us-central1");
        if (projectId == null || projectId.isBlank()) {
            return ValidationResult.failure("project-id is required");
        }
        try {
            GoogleCredentials googleCreds = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
            googleCreds.refreshIfExpired();
            String token = googleCreds.getAccessToken().getTokenValue();

            String url = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/anthropic/models",
                region, projectId, region);

            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return ValidationResult.failure("Authentication failed — check ADC credentials");
            }
            if (response.statusCode() != 200) {
                return ValidationResult.failure("Vertex AI returned status " + response.statusCode());
            }
            return ValidationResult.success(parseModelsResponse(response.body()));
        } catch (Exception e) {
            return ValidationResult.failure("Failed to connect to Vertex AI: " + e.getMessage());
        }
    }

    List<ModelDescriptor> parseModelsResponse(String json) {
        try {
            var seedModels = seedLookup.apply(
                ModelQuery.builder().vendor("anthropic").build());
            Map<String, ModelDescriptor> seedIndex = new HashMap<>();
            for (var m : seedModels) {
                seedIndex.put(m.apiModelId(), m);
            }

            JsonNode root = MAPPER.readTree(json);
            JsonNode models = root.path("models");
            if (!models.isArray()) {
                models = root.path("data");
            }
            if (!models.isArray()) return List.of();

            List<ModelDescriptor> result = new ArrayList<>();
            for (JsonNode node : models) {
                String id = node.has("name") ? extractModelId(node.get("name").asText()) : node.get("id").asText();
                String name = node.has("displayName") ? node.get("displayName").asText() : id;

                ModelDescriptor seed = seedIndex.get(id);
                if (seed != null) {
                    result.add(new ModelDescriptor(
                        id, id, seed.backendKey(), null, seed.vendor(), seed.family(), name,
                        seed.tier(), seed.capabilities(), seed.contextWindow(), seed.maxOutput(),
                        seed.locality(), seed.costTier(), seed.authMethod(), seed.properties()));
                } else {
                    result.add(new ModelDescriptor(
                        id, id, "claude", null, "anthropic", "claude", name,
                        ModelTier.STANDARD, Set.of(ModelCapabilities.TEXT), 0, 0,
                        ModelLocality.CLOUD, null, "gcp-adc", Map.of()));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractModelId(String fullName) {
        int lastSlash = fullName.lastIndexOf('/');
        return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
    }
}
