package io.casehub.platform.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelSource;
import io.casehub.platform.api.model.ModelTier;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SeedCatalogModelSource implements ModelSource {

    private static final Logger LOG = Logger.getLogger(SeedCatalogModelSource.class);
    private static final String CATALOG_PATH = "models/seed-catalog.yaml";
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Override
    public String sourceId() { return "seed-catalog"; }

    @Override
    public int priority() { return 0; }

    @Override
    public List<ModelDescriptor> refresh() {
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(CATALOG_PATH)) {
            if (is == null) return List.of();
            return parseCatalog(is);
        } catch (IOException e) {
            LOG.warnf("Failed to read seed catalog: %s", e.getMessage());
            return List.of();
        }
    }

    private List<ModelDescriptor> parseCatalog(InputStream is) throws IOException {
        JsonNode root = yaml.readTree(is);
        JsonNode models = root.path("models");
        if (!models.isArray()) return List.of();

        List<ModelDescriptor> result = new ArrayList<>();
        for (JsonNode node : models) {
            result.add(parseModel(node));
        }
        return result;
    }

    private ModelDescriptor parseModel(JsonNode node) {
        Set<String> capabilities = new HashSet<>();
        JsonNode    capsNode     = node.path("capabilities");
        if (capsNode.isArray()) {
            capsNode.forEach(n -> capabilities.add(n.asText()));
        }

        Map<String, String> properties = new LinkedHashMap<>();
        JsonNode            propsNode  = node.path("properties");
        if (propsNode.isObject()) {
            propsNode.fields().forEachRemaining(e -> properties.put(e.getKey(), e.getValue().asText()));
        }

        String id         = node.get("id").asText();
        String apiModelId = node.has("apiModelId") ? node.get("apiModelId").asText() : id;
        String instanceId = node.has("instanceId") ? node.get("instanceId").asText(null) : null;

        return new ModelDescriptor(
                id,
                apiModelId,
                node.get("backendKey").asText(),
                instanceId,
                node.get("vendor").asText(),
                node.get("family").asText(),
                node.get("displayName").asText(),
                ModelTier.valueOf(node.get("tier").asText()),
                capabilities,
                node.get("contextWindow").asInt(),
                node.get("maxOutput").asInt(),
                ModelLocality.valueOf(node.get("locality").asText()),
                node.has("costTier") && !node.get("costTier").isNull()
                ? CostTier.valueOf(node.get("costTier").asText()) : null,
                node.has("authMethod") ? node.get("authMethod").asText(null) : null,
                properties
        );
    }
}
