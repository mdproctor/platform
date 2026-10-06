package io.casehub.yaml.step.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.plugin.api.Action;
import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.Parameter;
import io.casehub.yaml.plugin.api.ParameterType;
import io.casehub.yaml.plugin.api.PluginRegistry;
import io.casehub.yaml.plugin.api.Portability;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class AptPluginSource {

    private static final String MANIFEST_DIR = "META-INF/yaml-plugins/";

    private final ObjectMapper objectMapper;

    public AptPluginSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void populate(PluginRegistry registry) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> dirs = cl.getResources(MANIFEST_DIR);
            while (dirs.hasMoreElements()) {
                URL dirUrl = dirs.nextElement();
                scanDirectory(dirUrl, cl, registry);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan APT plugin manifests", e);
        }
    }

    private void scanDirectory(URL dirUrl, ClassLoader cl, PluginRegistry registry)
            throws IOException {
        String protocol = dirUrl.getProtocol();
        if ("file".equals(protocol)) {
            scanFileDirectory(new File(dirUrl.getPath()), cl, registry);
        } else if ("jar".equals(protocol)) {
            scanJarDirectory(dirUrl, cl, registry);
        }
    }

    private void scanFileDirectory(File dir, ClassLoader cl, PluginRegistry registry)
            throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json") && !name.endsWith(".schema.json")) {
                String pluginName = name.substring(0, name.length() - ".json".length());
                loadAndRegister(pluginName, cl, registry);
            }
        }
    }

    private void scanJarDirectory(URL dirUrl, ClassLoader cl, PluginRegistry registry)
            throws IOException {
        JarURLConnection jarConn = (JarURLConnection) dirUrl.openConnection();
        try (JarFile jarFile = jarConn.getJarFile()) {
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry jarEntry = jarEntries.nextElement();
                String entryName = jarEntry.getName();
                if (entryName.startsWith(MANIFEST_DIR)
                        && entryName.endsWith(".json")
                        && !entryName.endsWith(".schema.json")
                        && !jarEntry.isDirectory()) {
                    String fileName = entryName.substring(MANIFEST_DIR.length());
                    String pluginName = fileName.substring(0, fileName.length() - ".json".length());
                    loadAndRegister(pluginName, cl, registry);
                }
            }
        }
    }

    private void loadAndRegister(String pluginName, ClassLoader cl, PluginRegistry registry)
            throws IOException {
        String manifestPath = MANIFEST_DIR + pluginName + ".json";
        String schemaPath = MANIFEST_DIR + pluginName + ".schema.json";

        try (InputStream manifestStream = cl.getResourceAsStream(manifestPath);
             InputStream schemaStream = cl.getResourceAsStream(schemaPath)) {
            if (manifestStream == null) return;
            Definition definition = loadManifest(pluginName, manifestStream, schemaStream);
            registry.register(definition);
        }
    }

    @SuppressWarnings("unchecked")
    Definition loadManifest(String name, InputStream manifestStream,
                            InputStream schemaStream) throws IOException {
        Map<String, Object> manifest = objectMapper.readValue(manifestStream, LinkedHashMap.class);
        String actionClass = (String) manifest.get("actionClass");
        String portabilityStr = (String) manifest.get("portability");
        Portability portability = portabilityStr != null
                ? Portability.valueOf(portabilityStr) : Portability.JAVA;
        String capabilityStr = (String) manifest.get("capability");

        Definition.Builder builder = Definition.of(name)
                .portability(portability)
                .capability(capabilityStr)
                .execute(loadAction(actionClass));

        if (schemaStream != null) {
            Map<String, Object> schema = objectMapper.readValue(schemaStream, LinkedHashMap.class);
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            if (properties != null) {
                for (Map.Entry<String, Object> prop : properties.entrySet()) {
                    Map<String, Object> propSchema = (Map<String, Object>) prop.getValue();
                    String typeStr = (String) propSchema.getOrDefault("type", "string");
                    builder.input(prop.getKey(), new Parameter(
                            ParameterType.fromString(typeStr), false, null, null, null,
                            (String) propSchema.get("description")));
                }
            }
        }

        return builder.build();
    }

    private Action loadAction(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return (Action) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate StepAction: " + className, e);
        }
    }
}
