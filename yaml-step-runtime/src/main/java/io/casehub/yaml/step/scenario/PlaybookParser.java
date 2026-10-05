package io.casehub.yaml.step.scenario;

import io.casehub.yaml.core.playbook.PlaybookDocument;
import io.casehub.yaml.core.playbook.PlaybookFrontMatter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PlaybookParser {

    private static final String PLAYBOOK_KEY = "playbook";
    private static final String SCHEMA_KEY = "schema";
    private static final String NAME_KEY = "name";

    private PlaybookParser() {}

    @SuppressWarnings("unchecked")
    public static PlaybookDocument parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("YAML content must not be empty");
        }

        var loader = new org.yaml.snakeyaml.Yaml();
        var documents = new ArrayList<Object>();
        for (Object doc : loader.loadAll(yaml)) {
            documents.add(doc);
        }

        if (documents.isEmpty() || documents.get(0) == null) {
            throw new IllegalArgumentException("YAML content must not be empty");
        }

        if (documents.size() == 1) {
            var root = asMap(documents.get(0));
            return new PlaybookDocument(null, root);
        }

        var firstDoc = asMap(documents.get(0));

        if (!firstDoc.containsKey(PLAYBOOK_KEY)) {
            var merged = new LinkedHashMap<String, Object>(firstDoc);
            var secondDoc = asMap(documents.get(1));
            merged.putAll(secondDoc);
            return new PlaybookDocument(null, merged);
        }

        var content = asMap(documents.get(1));
        var frontMatter = extractFrontMatter(firstDoc);
        return new PlaybookDocument(frontMatter, content);
    }

    private static PlaybookFrontMatter extractFrontMatter(Map<String, Object> doc) {
        String version = String.valueOf(doc.get(PLAYBOOK_KEY));
        String schema = (String) doc.get(SCHEMA_KEY);
        if (schema == null) {
            throw new IllegalArgumentException("Playbook front matter requires a 'schema' field");
        }
        String name = (String) doc.get(NAME_KEY);

        var metadata = new LinkedHashMap<String, Object>();
        for (var entry : doc.entrySet()) {
            if (!PLAYBOOK_KEY.equals(entry.getKey())
                    && !SCHEMA_KEY.equals(entry.getKey())
                    && !NAME_KEY.equals(entry.getKey())) {
                metadata.put(entry.getKey(), entry.getValue());
            }
        }

        return new PlaybookFrontMatter(version, schema, name,
                metadata.isEmpty() ? Map.of() : metadata);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        throw new IllegalArgumentException("YAML document must be a mapping, got: "
                + (obj != null ? obj.getClass().getSimpleName() : "null"));
    }
}
