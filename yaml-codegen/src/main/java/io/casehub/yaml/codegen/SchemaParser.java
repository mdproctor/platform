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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SchemaParser {

    private final ObjectMapper yaml = YamlMappers.create();

    public TypeGraph parse(File schemaFile) {
        try {
            JsonNode root = yaml.readTree(schemaFile);
            JsonNode defs = root.path("$defs");
            if (defs.isMissingNode()) {
                return new TypeGraph(List.of());
            }

            List<TypeGraph.TypeDef> types = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = defs.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                types.add(parseTypeDef(entry.getKey(), entry.getValue()));
            }
            return new TypeGraph(List.copyOf(types));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private TypeGraph.TypeDef parseTypeDef(String name, JsonNode typeNode) {
        List<TypeGraph.FieldDef> fields = new ArrayList<>();
        JsonNode properties = typeNode.path("properties");
        JsonNode requiredArray = typeNode.path("required");
        List<String> requiredFields = new ArrayList<>();
        if (requiredArray.isArray()) {
            requiredArray.forEach(n -> requiredFields.add(n.asText()));
        }

        Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            fields.add(parseFieldDef(entry.getKey(), entry.getValue(),
                    requiredFields.contains(entry.getKey())));
        }

        JsonNode additionalProps = typeNode.path("additionalProperties");
        boolean hasAdditional = additionalProps.isObject() || additionalProps.asBoolean(false);
        String additionalType = null;
        if (additionalProps.isObject()) {
            if (additionalProps.has("$ref")) {
                additionalType = extractRefName(additionalProps.get("$ref").asText());
            } else if (additionalProps.has("type")) {
                additionalType = additionalProps.get("type").asText();
            }
        } else if (additionalProps.asBoolean(false)) {
            additionalType = "object";
        }

        return new TypeGraph.TypeDef(name, List.copyOf(fields), hasAdditional, additionalType);
    }

    private TypeGraph.FieldDef parseFieldDef(String name, JsonNode fieldNode, boolean required) {
        String schemaType = null;
        String refTarget = null;
        boolean isArray = false;
        boolean isMap = false;
        String mapValueType = null;
        String description =
                fieldNode.has("description") ? fieldNode.get("description").asText() : null;

        if (fieldNode.has("$ref")) {
            refTarget = extractRefName(fieldNode.get("$ref").asText());
        } else if (fieldNode.has("type")) {
            String type = fieldNode.get("type").asText();
            if ("array".equals(type)) {
                isArray = true;
                JsonNode items = fieldNode.path("items");
                if (items.has("$ref")) {
                    refTarget = extractRefName(items.get("$ref").asText());
                } else if (items.has("type")) {
                    schemaType = items.get("type").asText();
                }
            } else if ("object".equals(type) && !fieldNode.has("properties")) {
                JsonNode addProps = fieldNode.path("additionalProperties");
                if (addProps.isObject() || addProps.asBoolean(false)) {
                    isMap = true;
                    if (addProps.isObject() && addProps.has("type")) {
                        mapValueType = addProps.get("type").asText();
                    } else if (addProps.isObject() && addProps.has("$ref")) {
                        mapValueType = extractRefName(addProps.get("$ref").asText());
                    } else {
                        mapValueType = "object";
                    }
                } else {
                    schemaType = "object";
                }
            } else {
                schemaType = type;
            }
        } else if (fieldNode.has("oneOf")) {
            schemaType = "oneOf";
        }

        return new TypeGraph.FieldDef(
                name, schemaType, refTarget, isArray, isMap, mapValueType, required, description);
    }

    private static String extractRefName(String ref) {
        int lastSlash = ref.lastIndexOf('/');
        return lastSlash >= 0 ? ref.substring(lastSlash + 1) : ref;
    }
}
