package io.casehub.platform.simulation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.jackson.YamlMappers;
import io.casehub.platform.simulation.InvocationRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class YamlCorpusLoader implements CorpusLoader {

    private static final ObjectMapper YAML_MAPPER = YamlMappers.create();

    @Override
    public boolean supports(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, List<InvocationRecord<Object, Object>>> load(
            InputStream input, String defaultTenancyId) {
        try {
            Map<String, List<Map<String, Object>>> raw = YAML_MAPPER.readValue(input, Map.class);
            return CorpusEntryParser.parseCorpusMap(raw, defaultTenancyId);
        } catch (com.fasterxml.jackson.databind.exc.MismatchedInputException e) {
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse YAML corpus", e);
        }
    }

    public Map<String, List<InvocationRecord<Object, Object>>> loadFromPaths(
            List<String> paths, String defaultTenancyId) {
        Map<String, List<InvocationRecord<Object, Object>>> merged = new HashMap<>();
        for (String path : paths) {
            try (InputStream is = StreamResolver.openStream(path.trim())) {
                load(is, defaultTenancyId).forEach((qn, records) ->
                        merged.computeIfAbsent(qn, k -> new ArrayList<>()).addAll(records));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load corpus file: " + path, e);
            }
        }
        return merged;
    }
}
