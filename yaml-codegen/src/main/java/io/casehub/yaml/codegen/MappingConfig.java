/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.yaml.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.jackson.YamlMappers;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record MappingConfig(
        List<String> globalAnnotations,
        List<String> skipPatterns,
        Map<String, String> imports,
        Map<String, String> deserializers,
        Map<String, TypeMapping> types) {

    public Optional<TypeMapping> forType(String typeName) {
        return Optional.ofNullable(types.get(typeName));
    }

    public static MappingConfig empty() {
        return new MappingConfig(List.of(), List.of(), Map.of(), Map.of(), Map.of());
    }

    public static MappingConfig load(File file) {
        try {
            ObjectMapper yaml = YamlMappers.create();
            JsonNode     root = yaml.readTree(file);

            List<String> annotations = new ArrayList<>();
            JsonNode     globalAnns  = root.path("globalAnnotations");
            if (globalAnns.isArray()) {
                globalAnns.forEach(n -> annotations.add(n.asText()));
            }

            List<String> skipPatterns = new ArrayList<>();
            JsonNode     skipNode     = root.path("skipPatterns");
            if (skipNode.isArray()) {
                skipNode.forEach(n -> skipPatterns.add(n.asText()));
            }

            Map<String, String> imports       = readStringMap(root.path("imports"));
            Map<String, String> deserializers = readStringMap(root.path("deserializers"));

            Map<String, TypeMapping>              typeMap   = new HashMap<>();
            JsonNode                              typesNode = root.path("types");
            Iterator<Map.Entry<String, JsonNode>> it        = typesNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                typeMap.put(entry.getKey(), parseTypeMapping(entry.getValue()));
            }

            return new MappingConfig(List.copyOf(annotations), List.copyOf(skipPatterns),
                                     Map.copyOf(imports), Map.copyOf(deserializers), Map.copyOf(typeMap));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> readStringMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                map.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return Map.copyOf(map);
    }

    private static TypeMapping parseTypeMapping(JsonNode node) {
        String recordName = node.path("recordName").asText(null);
        String body       = node.path("body").asText(null);

        Map<String, FieldMapping>             fields     = new HashMap<>();
        JsonNode                              fieldsNode = node.path("fields");
        Iterator<Map.Entry<String, JsonNode>> it         = fieldsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            fields.put(entry.getKey(), parseFieldMapping(entry.getValue()));
        }

        List<FieldMapping> additional    = new ArrayList<>();
        JsonNode           addFieldsNode = node.path("additionalFields");
        if (addFieldsNode.isArray()) {
            for (JsonNode fieldNode : addFieldsNode) {
                String       name = fieldNode.get("name").asText();
                FieldMapping fm   = parseFieldMapping(fieldNode);
                fields.put(name, fm);
                additional.add(fm);
            }
        }

        return new TypeMapping(recordName, Map.copyOf(fields), List.copyOf(additional), body);
    }

    private static FieldMapping parseFieldMapping(JsonNode node) {
        String  name         = node.has("name") ? node.get("name").asText() : null;
        String  type         = node.has("type") ? node.get("type").asText() : null;
        String  deserializer = node.has("deserializer") ? node.get("deserializer").asText() : null;
        String  jsonProperty = node.has("jsonProperty") ? node.get("jsonProperty").asText() : null;
        boolean skip         = node.has("skip") && node.get("skip").asBoolean();
        String  defaultValue = node.has("defaultValue") ? node.get("defaultValue").asText() : null;

        List<String> aliases   = new ArrayList<>();
        JsonNode     aliasNode = node.path("alias");
        if (aliasNode.isArray()) {
            aliasNode.forEach(n -> aliases.add(n.asText()));
        } else if (aliasNode.isTextual()) {
            aliases.add(aliasNode.asText());
        }

        return new FieldMapping(name, type, deserializer, List.copyOf(aliases), jsonProperty, skip, defaultValue);
    }

    public record TypeMapping(
            String recordName,
            Map<String, FieldMapping> fields,
            List<FieldMapping> additionalFields,
            String body) {

        public TypeMapping(Map<String, FieldMapping> fields) {
            this(null, fields, List.of(), null);
        }

        public Optional<FieldMapping> forField(String fieldName) {
            return Optional.ofNullable(fields.get(fieldName));
        }
    }

    public record FieldMapping(
            String name,
            String type,
            String deserializer,
            List<String> aliases,
            String jsonProperty,
            boolean skip,
            String defaultValue) {}
}
