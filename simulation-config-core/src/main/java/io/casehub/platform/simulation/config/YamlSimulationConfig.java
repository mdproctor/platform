package io.casehub.platform.simulation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.jackson.YamlMappers;
import io.casehub.platform.simulation.ExhaustionPolicy;
import io.casehub.platform.simulation.InvocationRecord;
import io.casehub.platform.simulation.ProfileSource;
import io.casehub.platform.simulation.SimulationConfig;
import io.casehub.platform.simulation.SimulationConfigException;
import io.casehub.platform.simulation.SimulationProfile;
import io.casehub.platform.simulation.TemporalProfile;
import io.casehub.platform.simulation.TimedEntry;
import io.casehub.platform.simulation.TimedSequence;
import io.casehub.platform.simulation.inmem.InMemorySimulationCorpus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class YamlSimulationConfig implements SimulationConfig, ProfileSource {

    private static final ObjectMapper YAML_MAPPER = YamlMappers.create();

    private final String defaultTenancyId;
    private final Map<String, MethodConfig> methods;
    private final Map<String, ProfileConfig> profiles;
    private final Map<String, TemporalProfileConfig> temporalProfiles;
    private final double                             speed;


    public YamlSimulationConfig(InputStream yamlInput) {
        this(yamlInput, null);
    }

    @SuppressWarnings("unchecked")
    public YamlSimulationConfig(InputStream yamlInput, String defaultTenancyIdOverride) {
        try {
            Map<String, Object> root;
            try {
                root = YAML_MAPPER.readValue(yamlInput, Map.class);
            } catch (com.fasterxml.jackson.databind.exc.MismatchedInputException e) {
                root = null;
            }
            if (root == null) {
                root = Map.of();
            }

            String yamlTenancy = (String) root.get("default-tenancy-id");
            this.defaultTenancyId = defaultTenancyIdOverride != null
                    ? defaultTenancyIdOverride : yamlTenancy;

            this.speed = root.containsKey("speed")
                    ? ((Number) root.get("speed")).doubleValue() : 1.0;
            if (this.speed <= 0) {
                throw new SimulationConfigException("speed must be positive, got: " + this.speed);
            }

            this.methods = parseMethods(
                    (Map<String, Map<String, Object>>) root.get("methods"));

            this.temporalProfiles = parseTemporalProfiles(
                    (Map<String, Map<String, Object>>) root.get("temporal-profiles"));

            this.profiles = parseProfiles(
                    (Map<String, Map<String, Object>>) root.get("profiles"));

            warnUnknownKeys(root);

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse simulation YAML", e);
        }
    }

    @Override
    public Optional<String> strategyFor(String qualifiedName) {
        return Optional.ofNullable(methods.get(qualifiedName))
                .map(MethodConfig::strategy);
    }

    @Override
    public boolean captureEnabled(String qualifiedName) {
        return Optional.ofNullable(methods.get(qualifiedName))
                .map(mc -> Boolean.TRUE.equals(mc.capture()))
                .orElse(false);
    }

    @Override
    public Optional<ExhaustionPolicy> exhaustionPolicy(String qualifiedName) {
        return Optional.ofNullable(methods.get(qualifiedName))
                .map(MethodConfig::exhaustionPolicy);
    }

    @Override
    public Optional<Double> threshold(String qualifiedName) {
        return Optional.ofNullable(methods.get(qualifiedName))
                .map(MethodConfig::threshold);
    }

    @Override
    public double speed() {
        return speed;
    }


    public Map<String, String> extractorSpecs() {
        return methods.entrySet().stream()
                .filter(e -> e.getValue().keyExtractor() != null)
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().keyExtractor()));
    }

    public Map<String, String> scorerSpecs() {
        return methods.entrySet().stream()
                .filter(e -> e.getValue().scorer() != null)
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().scorer()));
    }

    public Optional<String> defaultTenancyId() {
        return Optional.ofNullable(defaultTenancyId);
    }

    public Set<String> profileNames() {
        return Collections.unmodifiableSet(profiles.keySet());
    }

    public Map<String, List<InvocationRecord<Object, Object>>> loadAllCorpus() {
        Map<String, List<InvocationRecord<Object, Object>>> result = new HashMap<>();
        methods.forEach((qn, mc) -> {
            List<InvocationRecord<Object, Object>> records = loadCorpusForMethod(qn, mc);
            if (!records.isEmpty()) {
                result.put(qn, records);
            }
        });
        return result;
    }

    public Map<String, List<InvocationRecord<Object, Object>>> loadAllCorpus(
            String activeProfile) {
        Map<String, List<InvocationRecord<Object, Object>>> result = loadAllCorpus();

        ProfileConfig profile = profiles.get(activeProfile);
        if (profile == null) {
            return result;
        }

        profile.methods().forEach((qn, mc) -> {
            List<InvocationRecord<Object, Object>> profileRecords =
                    loadCorpusForMethod(qn, mc);
            if (!profileRecords.isEmpty()) {
                result.computeIfAbsent(qn, k -> new ArrayList<>())
                        .addAll(profileRecords);
            }
        });

        if (profile.corpusFiles() != null && !profile.corpusFiles().isEmpty()) {
            loadExternalCorpusFiles(profile.corpusFiles())
                    .forEach((qn, records) ->
                            result.computeIfAbsent(qn, k -> new ArrayList<>())
                                    .addAll(records));
        }

        return result;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Optional<SimulationProfile> resolve(String name) {
        ProfileConfig profile = profiles.get(name);
        if (profile == null) {
            return Optional.empty();
        }

        SimulationConfig composedConfig = new SimulationConfig() {
            @Override
            public Optional<String> strategyFor(String qualifiedName) {
                MethodConfig pm = profile.methods().get(qualifiedName);
                if (pm != null && pm.strategy() != null) {
                    return Optional.of(pm.strategy());
                }
                return YamlSimulationConfig.this.strategyFor(qualifiedName);
            }

            @Override
            public boolean captureEnabled(String qualifiedName) {
                MethodConfig pm = profile.methods().get(qualifiedName);
                if (pm != null && pm.capture() != null) {
                    return pm.capture();
                }
                return YamlSimulationConfig.this.captureEnabled(qualifiedName);
            }

            @Override
            public Optional<ExhaustionPolicy> exhaustionPolicy(String qualifiedName) {
                MethodConfig pm = profile.methods().get(qualifiedName);
                if (pm != null && pm.exhaustionPolicy() != null) {
                    return Optional.of(pm.exhaustionPolicy());
                }
                return YamlSimulationConfig.this.exhaustionPolicy(qualifiedName);
            }

            @Override
            public Optional<Double> threshold(String qualifiedName) {
                MethodConfig pm = profile.methods().get(qualifiedName);
                if (pm != null && pm.threshold() != null) {
                    return Optional.of(pm.threshold());
                }
                return YamlSimulationConfig.this.threshold(qualifiedName);
            }
        };

        InMemorySimulationCorpus corpus = new InMemorySimulationCorpus<>();
        profile.methods().keySet().forEach(qn -> {
            MethodConfig baseMc = methods.get(qn);
            if (baseMc != null) {
                List<InvocationRecord<Object, Object>> baseRecords =
                        loadCorpusForMethod(qn, baseMc);
                if (!baseRecords.isEmpty()) {
                    corpus.seed(qn, baseRecords);
                }
            }
        });
        profile.methods().forEach((qn, mc) -> {
            List<InvocationRecord<Object, Object>> profileRecords =
                    loadCorpusForMethod(qn, mc);
            if (!profileRecords.isEmpty()) {
                corpus.seed(qn, profileRecords);
            }
        });
        if (profile.corpusFiles() != null && !profile.corpusFiles().isEmpty()) {
            loadExternalCorpusFiles(profile.corpusFiles())
                    .forEach(corpus::seed);
        }

        return Optional.of(new SimulationProfile(composedConfig, corpus));
    }

    @SuppressWarnings("unchecked")
    private Map<String, MethodConfig> parseMethods(Map<String, Map<String, Object>> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, MethodConfig> result = new LinkedHashMap<>();
        raw.forEach((qn, props) -> result.put(qn, parseMethodConfig(props)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private MethodConfig parseMethodConfig(Map<String, Object> props) {
        String strategy = (String) props.get("strategy");
        Boolean capture = props.containsKey("capture")
                ? Boolean.TRUE.equals(props.get("capture")) : null;
        ExhaustionPolicy ep = props.containsKey("exhaustion-policy")
                ? ExhaustionPolicy.valueOf(
                ((String) props.get("exhaustion-policy")).toUpperCase().replace("-", "_"))
                : null;
        String keyExtractor = (String) props.get("key-extractor");
        String scorer = (String) props.get("scorer");
        Double threshold = props.containsKey("threshold")
                ? ((Number) props.get("threshold")).doubleValue() : null;

        List<CorpusEntry> corpus = new ArrayList<>();
        List<Map<String, Object>> rawCorpus =
                (List<Map<String, Object>>) props.get("corpus");
        if (rawCorpus != null) {
            for (Map<String, Object> entry : rawCorpus) {
                if (!entry.containsKey("output")) {
                    throw new IllegalArgumentException(
                            "Corpus entry missing required 'output' field");
                }
                corpus.add(new CorpusEntry(
                        (String) entry.get("key"),
                        (String) entry.get("tenancy-id"),
                        entry.get("input"),
                        entry.get("output")));
            }
        }

        List<String> corpusFiles = (List<String>) props.get("corpus-files");

        return new MethodConfig(strategy, capture, ep, keyExtractor, scorer,
                threshold, corpus, corpusFiles);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ProfileConfig> parseProfiles(
            Map<String, Map<String, Object>> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, ProfileConfig> result = new LinkedHashMap<>();
        raw.forEach((name, props) -> {
            Map<String, MethodConfig> profileMethods = parseMethods(
                    (Map<String, Map<String, Object>>) props.get("methods"));
            List<String> corpusFiles = (List<String>) props.get("corpus-files");
            List<TemporalProfileConfig> temporal = parseTemporalList(
                    (List<Object>) props.get("temporal"));
            result.put(name, new ProfileConfig(profileMethods, corpusFiles, temporal));
        });
        return result;
    }

    private List<InvocationRecord<Object, Object>> loadCorpusForMethod(
            String qualifiedName, MethodConfig mc) {
        List<InvocationRecord<Object, Object>> records = new ArrayList<>();

        if (mc.corpus() != null) {
            for (CorpusEntry entry : mc.corpus()) {
                String tenancyId = entry.tenancyId() != null
                        ? entry.tenancyId() : defaultTenancyId;
                records.add(new InvocationRecord<>(
                        tenancyId, entry.key(), entry.input(),
                        entry.output(), Instant.now()));
            }
        }

        if (mc.corpusFiles() != null && !mc.corpusFiles().isEmpty()) {
            Map<String, List<InvocationRecord<Object, Object>>> external =
                    loadExternalCorpusFiles(mc.corpusFiles());
            List<InvocationRecord<Object, Object>> forMethod =
                    external.get(qualifiedName);
            if (forMethod != null) {
                records.addAll(forMethod);
            }
        }

        return records;
    }

    private Map<String, List<InvocationRecord<Object, Object>>> loadExternalCorpusFiles(
            List<String> paths) {
        var composite = new CompositeCorpusLoader(
                new YamlCorpusLoader(), new JsonCorpusLoader(), new CsvCorpusLoader());
        return composite.loadFromPaths(paths, defaultTenancyId);
    }


    public Map<String, TemporalProfileConfig> temporalProfiles() {
        return Collections.unmodifiableMap(temporalProfiles);
    }

    public Optional<TemporalProfile<Map<String, Object>>> resolveTemporalProfile(String name) {
        TemporalProfileConfig tpc = temporalProfiles.get(name);
        if (tpc == null) {return Optional.empty();}
        return Optional.of(resolveTemporalProfileConfig(name, tpc, new HashSet<>()));
    }

    public List<TemporalProfileConfig> temporalForProfile(String profileName) {
        ProfileConfig profile = profiles.get(profileName);
        if (profile == null) {return List.of();}
        return profile.temporal() != null ? profile.temporal() : List.of();
    }

    @SuppressWarnings("unchecked")
    private TemporalProfile<Map<String, Object>> resolveTemporalProfileConfig(
            String name, TemporalProfileConfig tpc, Set<String> visited) {
        if (!visited.add(name)) {
            throw new SimulationConfigException(
                    "Circular temporal profile reference: " + name);
        }

        TimedSequence<Map<String, Object>> sequence;

        if (tpc.events() != null && !tpc.events().isEmpty()) {
            var entries = tpc.events().stream()
                             .map(e -> new TimedEntry<>(
                                     e.payload(),
                                     DurationParser.parse(e.delay()),
                                     e.label()))
                             .toList();
            sequence = new TimedSequence<>(entries);
        } else if (tpc.eventsFile() != null) {
            sequence = loadTemporalEventsFromFile(tpc.eventsFile());
        } else if (tpc.fromCorpus() != null) {
            List<InvocationRecord<Object, Object>> records = loadCorpusForTemporalProfile(tpc.fromCorpus());
            TimedSequence<Object> raw = TimedSequence.fromRecorded(records);
            sequence = new TimedSequence<>(raw.entries().stream()
                    .map(e -> new TimedEntry<>((Map<String, Object>) e.event(), e.delay(), e.label(), e.qualifiedName()))
                    .toList());
        } else if (tpc.sequence() != null && !tpc.sequence().isEmpty()) {
            sequence = resolveSequenceRefs(tpc.sequence(), visited);
        } else {
            sequence = new TimedSequence<>(List.of());
        }

        String tenancyId = tpc.tenancyId() != null ? tpc.tenancyId() : defaultTenancyId;
        return new TemporalProfile<>(name, tpc.qualifiedName(), tenancyId,
                                     sequence, tpc.loop(), tpc.speed() > 0 ? tpc.speed() : 1.0);
    }

    private TimedSequence<Map<String, Object>> resolveSequenceRefs(
            List<SequenceRef> refs, Set<String> visited) {
        var allEntries = new ArrayList<TimedEntry<Map<String, Object>>>();
        for (SequenceRef ref : refs) {
            TemporalProfileConfig refConfig = temporalProfiles.get(ref.ref());
            if (refConfig == null) {
                throw new SimulationConfigException(
                        "Unknown temporal profile ref: " + ref.ref());
            }
            TemporalProfile<Map<String, Object>> resolved =
                    resolveTemporalProfileConfig(ref.ref(), refConfig, new HashSet<>(visited));

            List<TimedEntry<Map<String, Object>>> refEntries = resolved.sequence().entries();
            if (!refEntries.isEmpty()) {
                for (int i = 0; i < refEntries.size(); i++) {
                    TimedEntry<Map<String, Object>> entry = refEntries.get(i);
                    String entryQN = entry.qualifiedName() != null
                                     ? entry.qualifiedName() : resolved.qualifiedName();
                    if (i == 0 && ref.delay() != null) {
                        java.time.Duration gap = DurationParser.parse(ref.delay());
                        allEntries.add(new TimedEntry<>(entry.event(),
                                                        entry.delay().plus(gap), entry.label(), entryQN));
                    } else {
                        allEntries.add(new TimedEntry<>(entry.event(),
                                                        entry.delay(), entry.label(), entryQN));
                    }
                }
            }
        }
        return new TimedSequence<>(allEntries);
    }

    @SuppressWarnings("unchecked")
    private TimedSequence<Map<String, Object>> loadTemporalEventsFromFile(String path) {
        InputStream is = StreamResolver.openStream(path);
        if (is == null) {
            throw new SimulationConfigException(
                    "Temporal events file not found: " + path);
        }
        try (is) {
            List<Map<String, Object>> rawEvents = YAML_MAPPER.readValue(is, List.class);
            var entries = rawEvents.stream()
                                   .map(e -> new TimedEntry<>(
                                           (Map<String, Object>) e.get("payload"),
                                           DurationParser.parse(String.valueOf(e.getOrDefault("delay", "0"))),
                                           (String) e.get("label")))
                                   .toList();
            return new TimedSequence<>(entries);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load temporal events from " + path, e);
        }
    }

    private List<InvocationRecord<Object, Object>> loadCorpusForTemporalProfile(String qn) {
        MethodConfig mc = methods.get(qn);
        if (mc == null) {return List.of();}
        return loadCorpusForMethod(qn, mc);
    }

    @SuppressWarnings("unchecked")
    private Map<String, TemporalProfileConfig> parseTemporalProfiles(
            Map<String, Map<String, Object>> raw) {
        if (raw == null) {return Map.of();}
        Map<String, TemporalProfileConfig> result = new LinkedHashMap<>();
        raw.forEach((name, props) -> result.put(name, parseTemporalProfileConfig(props)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private TemporalProfileConfig parseTemporalProfileConfig(Map<String, Object> props) {
        String  qualifiedName = (String) props.get("qualified-name");
        String  tenancyId     = (String) props.get("tenancy-id");
        boolean loop          = Boolean.TRUE.equals(props.get("loop"));
        double speed = props.containsKey("speed")
                       ? ((Number) props.get("speed")).doubleValue() : 0;
        String eventsFile = (String) props.get("events-file");
        String fromCorpus = (String) props.get("from-corpus");

        List<TemporalEventConfig> events    = null;
        List<Map<String, Object>> rawEvents = (List<Map<String, Object>>) props.get("events");
        if (rawEvents != null) {
            events = rawEvents.stream()
                              .map(e -> new TemporalEventConfig(
                                      String.valueOf(e.getOrDefault("delay", "0")),
                                      (String) e.get("label"),
                                      (Map<String, Object>) e.get("payload")))
                              .toList();
        }

        List<SequenceRef>         sequence = null;
        List<Map<String, Object>> rawSeq   = (List<Map<String, Object>>) props.get("sequence");
        if (rawSeq != null) {
            sequence = rawSeq.stream()
                             .map(s -> new SequenceRef(
                                     (String) s.get("ref"),
                                     s.containsKey("delay") ? String.valueOf(s.get("delay")) : null))
                             .toList();
        }

        return new TemporalProfileConfig(qualifiedName, tenancyId, loop, speed,
                                         events, eventsFile, fromCorpus, sequence);
    }

    @SuppressWarnings("unchecked")
    private List<TemporalProfileConfig> parseTemporalList(List<Object> raw) {
        if (raw == null) {return List.of();}
        return raw.stream().map(item -> {
            Map<String, Object> props = (Map<String, Object>) item;
            if (props.containsKey("ref") && props.size() == 1) {
                String                ref       = (String) props.get("ref");
                TemporalProfileConfig refConfig = temporalProfiles.get(ref);
                if (refConfig == null) {
                    throw new SimulationConfigException(
                            "Unknown temporal profile ref: " + ref);
                }
                return refConfig;
            }
            return parseTemporalProfileConfig(props);
        }).toList();
    }

    private static final Set<String> KNOWN_TOP_LEVEL_KEYS =
            Set.of("default-tenancy-id", "methods", "profiles", "temporal-profiles", "speed");

    private static void warnUnknownKeys(Map<String, Object> root) {
        for (String key : root.keySet()) {
            if (!KNOWN_TOP_LEVEL_KEYS.contains(key)) {
                java.util.logging.Logger.getLogger(YamlSimulationConfig.class.getName())
                        .warning("Unknown top-level key in simulation.yaml: '" + key
                                + "'. Known keys: " + KNOWN_TOP_LEVEL_KEYS);
            }
        }
    }

    record MethodConfig(
            String strategy,
            Boolean capture,
            ExhaustionPolicy exhaustionPolicy,
            String keyExtractor,
            String scorer,
            Double threshold,
            List<CorpusEntry> corpus,
            List<String> corpusFiles) {}

    record CorpusEntry(
            String key,
            String tenancyId,
            Object input,
            Object output) {}

    record ProfileConfig(
            Map<String, MethodConfig> methods,
            List<String> corpusFiles,
            List<TemporalProfileConfig> temporal) {}
}
