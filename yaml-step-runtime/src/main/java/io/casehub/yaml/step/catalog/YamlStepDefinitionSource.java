package io.casehub.yaml.step.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.jackson.YamlMappers;
import io.casehub.yaml.core.step.Declaration;
import io.casehub.yaml.core.step.DeclarationFile;
import io.casehub.yaml.core.step.DeclarationParser;
import io.casehub.yaml.core.step.InvokeBinding;
import io.casehub.yaml.plugin.api.Action;
import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.step.ActionExecutionEvent;
import io.casehub.yaml.step.InvokeHandler;
import io.casehub.yaml.step.ValidatingAction;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class YamlStepDefinitionSource {

    private final List<String> definitionFiles;
    private final List<InvokeHandler> handlers;
    private final Consumer<ActionExecutionEvent> eventSink;

    public YamlStepDefinitionSource(List<String> definitionFiles,
                                    List<InvokeHandler> handlers,
                                    Consumer<ActionExecutionEvent> eventSink) {
        this.definitionFiles = definitionFiles;
        this.handlers = handlers;
        this.eventSink = eventSink;
    }

    public void populate(PluginRegistry registry) {
        for (String file : definitionFiles) {
            try {
                loadFile(file, registry);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to load step definition file: " + file, e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFile(String path, PluginRegistry registry) throws IOException {
        InputStream is = Thread.currentThread().getContextClassLoader()
                               .getResourceAsStream(path);
        if (is == null) {
            throw new IOException("Step definition file not found on classpath: " + path);
        }

        Map<String, Object> raw;
        try (is) {
            ObjectMapper yamlMapper = YamlMappers.create();
            raw = yamlMapper.readValue(is, Map.class);
        }

        Map<String, Object> actionsRaw = (Map<String, Object>) raw.get("actions");
        if (actionsRaw == null) {
            Map<String, Object> adjusted = new java.util.LinkedHashMap<>();
            adjusted.put("namespace", raw.getOrDefault("namespace", ""));
            Map<String, Object> actionEntries = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (!"namespace".equals(entry.getKey())) {
                    actionEntries.put(entry.getKey(), entry.getValue());
                }
            }
            adjusted.put("actions", actionEntries);
            raw = adjusted;
        }

        DeclarationFile defFile = DeclarationParser.parse(raw);

        for (Map.Entry<String, Declaration> entry : defFile.actions().entrySet()) {
            Declaration decl    = entry.getValue();
            InvokeBinding binding = decl.invoke();

            Action action    = resolveHandler(binding).create(decl, binding);
            Action validated = new ValidatingAction(decl, action, eventSink);

            Portability portability = decl.portability() != null
                    ? decl.portability() : Portability.UNIVERSAL;

            String qualifiedName = decl.qualifiedName(defFile.namespace());
            registry.register(new Definition(qualifiedName, decl.description(),
                    decl.inputs(), decl.outputs(), portability, validated, null));
            if (!defFile.namespace().isEmpty()) {
                registry.register(new Definition(decl.name(), decl.description(),
                        decl.inputs(), decl.outputs(), portability, validated, null));
            }
        }
    }

    private InvokeHandler resolveHandler(InvokeBinding binding) {
        for (InvokeHandler handler : handlers) {
            if (handler.supports(binding)) {
                return handler;
            }
        }
        throw new IllegalStateException(
                "No InvokeHandler supports binding type: " + binding.getClass().getSimpleName());
    }
}
