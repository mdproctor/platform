package io.casehub.platform.llm.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.model.ModelCapabilities;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelTier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@ApplicationScoped
public class OpenAiClient implements VendorClient {

    private static final String API_URL = "https://api.openai.com/v1/models";
    private static final ObjectMapper MAPPER = new ObjectMapper();


    private final HttpClient                                  httpClient = HttpClient.newHttpClient();
    private final Function<ModelQuery, List<ModelDescriptor>> seedLookup;

    @Inject
    OpenAiClient(ModelRegistry registry) {
        this.seedLookup = registry::query;
    }

    OpenAiClient(Function<ModelQuery, List<ModelDescriptor>> seedLookup) {
        this.seedLookup = seedLookup;
    }

    @Override public String vendorKey() { return "openai"; }
    @Override public String backendKey() { return "openai"; }
    @Override public String displayName() { return "OpenAI"; }
    @Override public String authMethod() { return "api-key"; }
    @Override public List<String> requiredFields() { return List.of("api-key"); }

    @Override
    public ValidationResult listModels(Map<String, String> credentials) {
        String apiKey = credentials.get("api-key");
        if (apiKey == null || apiKey.isBlank()) {
            return ValidationResult.failure("api-key is required");
        }
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                return ValidationResult.failure("Invalid API key");
            }
            if (response.statusCode() != 200) {
                return ValidationResult.failure("OpenAI API returned status " + response.statusCode());
            }
            return ValidationResult.success(parseModelsResponse(response.body()));
        } catch (Exception e) {
            return ValidationResult.failure("Failed to connect to OpenAI API: " + e.getMessage());
        }
    }

    List<ModelDescriptor> parseModelsResponse(String json) {
        try {
            var seedModels = seedLookup.apply(
                ModelQuery.builder().vendor("openai").build());
            Map<String, ModelDescriptor> seedIndex = new java.util.HashMap<>();
            for (var m : seedModels) {
                seedIndex.put(m.apiModelId(), m);
            }

            JsonNode root = MAPPER.readTree(json);
            JsonNode data = root.path("data");
            if (!data.isArray()) return List.of();

            List<ModelDescriptor> result = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.get("id").asText();

                ModelDescriptor seed = seedIndex.get(id);
                if (seed != null) {
                    result.add(new ModelDescriptor(
                        id, id, seed.backendKey(), null, seed.vendor(), seed.family(), seed.displayName(),
                        seed.tier(), seed.capabilities(), seed.contextWindow(), seed.maxOutput(),
                        seed.locality(), seed.costTier(), seed.authMethod(), seed.properties()));
                } else {
                    result.add(new ModelDescriptor(
                        id, id, "openai", null, "openai", "openai", id,
                        ModelTier.STANDARD, Set.of(ModelCapabilities.TEXT), 0, 0,
                        ModelLocality.CLOUD, null, "api-key", Map.of()));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
