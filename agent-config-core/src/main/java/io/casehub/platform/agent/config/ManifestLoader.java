package io.casehub.platform.agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.jackson.YamlMappers;
import io.casehub.platform.api.model.ModelDescriptor;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ManifestLoader {

    private static final Logger LOG = Logger.getLogger(ManifestLoader.class);
    private static final int MAX_DEPTH = 3;
    private static final int MAX_SOURCES = 20;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper mapper;
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build();


    public ManifestLoader() {
        this.mapper = YamlMappers.create()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public Manifest loadResource(URI uri) {
        try (InputStream is = uri.toURL().openStream()) {
            return mapper.readValue(is, Manifest.class);
        } catch (IOException e) {
            LOG.warnf("Failed to load manifest from %s: %s", uri, e.getMessage());
            return emptyManifest();
        }
    }

    public Manifest loadFile(Path path) {
        if (!Files.exists(path)) return null;
        try {
            return mapper.readValue(path.toFile(), Manifest.class);
        } catch (IOException e) {
            LOG.warnf("Failed to parse manifest %s: %s", path, e.getMessage());
            return emptyManifest();
        }
    }

    public Manifest load(Path projectDir, String profile) {
        var entries = new ArrayList<PrioritizedManifest>();

        addClasspathResource(entries, "models/seed-catalog.yaml", 0);
        addFileIfExists(entries, Path.of("/etc/casehub/agent-config.yaml"), 10);
        addFileIfExists(entries, Path.of(System.getProperty("user.home"), ".casehub", "agent-config.yaml"), 20);
        if (profile != null) {
            addFileIfExists(entries, Path.of(System.getProperty("user.home"), ".casehub", "agent-config-" + profile + ".yaml"), 25);
        }
        if (projectDir != null) {
            addFileIfExists(entries, projectDir.resolve("agent-config.yaml"), 30);
            if (profile != null) {
                addFileIfExists(entries, projectDir.resolve("agent-config-" + profile + ".yaml"), 35);
            }
        }

        var loadedUris = new LinkedHashSet<String>();
        for (var entry : new ArrayList<>(entries)) {
            followSources(entry.manifest, entries, loadedUris, 1);
        }

        return merge(entries);
    }

    private void followSources(Manifest manifest, List<PrioritizedManifest> entries,
                               Set<String> loadedUris, int depth) {
        if (depth > MAX_DEPTH) return;
        for (var source : manifest.sources()) {
            if (loadedUris.size() >= MAX_SOURCES) {
                LOG.warnf("Max source limit (%d) reached — skipping %s", MAX_SOURCES, source.uri());
                return;
            }
            if (!loadedUris.add(source.uri())) {
                LOG.warnf("Cycle detected — already loaded %s", source.uri());
                continue;
            }
            var fetched = fetchRemote(source.uri());
            if (fetched != null) {
                entries.add(new PrioritizedManifest(fetched, source.priority()));
                followSources(fetched, entries, loadedUris, depth + 1);
            }
        }
    }

    private Manifest fetchRemote(String uri) {
        try {
            var request  = HttpRequest.newBuilder().uri(URI.create(uri)).timeout(FETCH_TIMEOUT).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                LOG.warnf("HTTP %d from %s", response.statusCode(), uri);
                return null;
            }
            try (var body = response.body()) {
                return mapper.readValue(body, Manifest.class);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to fetch remote source %s: %s", uri, e.getMessage());
            return null;
        }
    }

    private void addClasspathResource(List<PrioritizedManifest> entries, String resource, int priority) {
        var is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        if (is == null) return;
        try (is) {
            var manifest = mapper.readValue(is, Manifest.class);
            entries.add(new PrioritizedManifest(manifest, priority));
            LOG.debugf("Loaded classpath resource: %s (priority %d)", resource, priority);
        } catch (IOException e) {
            LOG.warnf("Failed to parse classpath resource %s: %s", resource, e.getMessage());
        }
    }

    private void addFileIfExists(List<PrioritizedManifest> entries, Path path, int priority) {
        var manifest = loadFile(path);
        if (manifest != null) {
            entries.add(new PrioritizedManifest(manifest, priority));
            LOG.infof("Loaded manifest: %s (priority %d)", path, priority);
        }
    }

    Manifest merge(List<PrioritizedManifest> entries) {
        entries.sort(Comparator.comparingInt(PrioritizedManifest::priority));

        var models = new LinkedHashMap<String, ModelDescriptor>();
        var providers = new LinkedHashMap<String, ProviderDeclaration>();
        var aliases = new LinkedHashMap<String, AliasDeclaration>();
        var localModels = new LinkedHashMap<String, LocalModelDeclaration>();
        var sources = new LinkedHashMap<String, SourceDeclaration>();
        ManifestDefaults defaults = null;

        for (var entry : entries) {
            var m = entry.manifest;
            for (var model : m.models()) models.put(model.id(), model);
            for (var provider : m.providers()) providers.put(provider.vendor(), provider);
            for (var alias : m.aliases().entrySet()) aliases.put(alias.getKey(), alias.getValue());
            for (var local : m.localModels()) localModels.put(local.id(), local);
            for (var source : m.sources()) sources.put(source.uri(), source);
            if (m.defaults() != null) defaults = m.defaults();
        }

        return new Manifest(
                new ArrayList<>(models.values()),
                new ArrayList<>(providers.values()),
                new ArrayList<>(sources.values()),
                aliases,
                new ArrayList<>(localModels.values()),
                defaults
        );
    }

    private static Manifest emptyManifest() {
        return new Manifest(List.of(), List.of(), List.of(), Map.of(), List.of(), null);
    }

    record PrioritizedManifest(Manifest manifest, int priority) {}
}
