package io.casehub.platform.simulation;

import io.casehub.platform.simulation.inmem.InMemorySimulationCorpus;
import io.casehub.platform.simulation.strategy.KeyLookupStrategy;
import io.casehub.platform.simulation.strategy.NearestMatchStrategy;
import io.casehub.platform.simulation.strategy.RandomStrategy;
import io.casehub.platform.simulation.strategy.RecordedReplayStrategy;
import io.casehub.platform.simulation.strategy.SequentialStrategy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressWarnings({"rawtypes", "unchecked"})
public class SimulationRuntime {

    private final SimulationConfig config;
    private final SimulationCorpus corpus;
    private final ConcurrentHashMap<String, KeyExtractor<?>> extractors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SimilarityScorer<?>> scorers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SimulationStrategy<?, ?>> strategyCache = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SimulationOverlay> overlayStack = new CopyOnWriteArrayList<>();
    private       ProfileSource                           profileSource;
    private volatile double                               globalSpeed;


    public SimulationRuntime(final SimulationConfig config, final SimulationCorpus corpus) {
        this.config = config;
        this.corpus = corpus;
        this.globalSpeed = config.speed();
    }

    public <I> void registerExtractor(final String qualifiedName, final KeyExtractor<I> extractor) {
        extractors.put(qualifiedName, extractor);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void apply(final CorpusSeed<?, ?> seed) {
        ((CorpusSeed) seed).seedInto(corpus);
        if (seed.keyExtractor() != null) {
            registerExtractor(seed.qualifiedName(), seed.keyExtractor());
        }
    }

    public <I> void registerScorer(final String qualifiedName, final SimilarityScorer<I> scorer) {
        scorers.put(qualifiedName, scorer);
    }

    public <I, O> Optional<SimulationStrategy<I, O>> strategyFor(final String qualifiedName) {
        var stack = overlayStack;
        for (int i = stack.size() - 1; i >= 0; i--) {
            var overlay         = stack.get(i);
            var overlayStrategy = overlay.config().strategyFor(qualifiedName);
            if (overlayStrategy.isPresent()) {
                return Optional.of((SimulationStrategy<I, O>) overlay.strategyCache()
                                                                     .computeIfAbsent(qualifiedName, qn ->
                                                                                                             createStrategy(qn, overlayStrategy.get(), overlay.corpus())));
            }
        }
        return config.strategyFor(qualifiedName)
                     .map(strategyName -> (SimulationStrategy<I, O>) strategyCache.computeIfAbsent(
                             qualifiedName, qn -> createStrategy(qn, strategyName, corpus)));
    }

    public boolean captureEnabled(final String qualifiedName) {
        return config.captureEnabled(qualifiedName);
    }

    public <I, O> void capture(final String qualifiedName, final String tenancyId,
                               final I input, final O output) {
        corpus.record(qualifiedName, tenancyId, input, output);
    }

    public <I, O> void capture(final String qualifiedName, final String tenancyId,
                               final String key, final I input, final O output) {
        corpus.record(qualifiedName, tenancyId, key, input, output);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public SimulationOverlay pushOverlay(final SimulationConfig config, final SimulationCorpus corpus) {
        final var overlay = new SimulationOverlay(config, corpus);
        overlayStack.add(overlay);
        strategyCache.clear();
        return overlay;
    }

    public SimulationOverlay pushOverlay(final SimulationConfig config) {
        return pushOverlay(config, new InMemorySimulationCorpus<>());
    }

    public void popOverlay(final SimulationOverlay overlay) {
        if (!overlayStack.remove(overlay)) {
            throw new IllegalArgumentException("Overlay not found in stack");
        }
        strategyCache.clear();
    }

    public void popAll() {
        overlayStack.clear();
        strategyCache.clear();
    }

    public List<JournalEntry> journal(final SimulationOverlay overlay) {
        return overlay.journal().entries();
    }

    public boolean hasActiveOverlay() {
        return !overlayStack.isEmpty();
    }

    public double globalSpeed() {
        return globalSpeed;
    }

    public void setGlobalSpeed(double speed) {
        if (speed <= 0) {throw new IllegalArgumentException("speed must be positive");}
        this.globalSpeed = speed;
    }


    public void setProfileSource(final ProfileSource profileSource) {
        this.profileSource = profileSource;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public SimulationOverlay pushProfile(final String name) {
        if (profileSource == null) {
            throw new SimulationConfigException(
                    "No ProfileSource registered — cannot resolve profile '" + name + "'");
        }
        final SimulationProfile profile = profileSource.resolve(name)
                                                       .orElseThrow(() -> new SimulationConfigException(
                                                               "Unknown simulation profile: '" + name + "'"));
        return pushOverlay(profile.config(), (SimulationCorpus) profile.corpus());
    }


    public void recordJournal(final String qualifiedName, final String tenancyId,
                               final Object input, final Object output, final DataRealism dataRealism) {
        final var stack = overlayStack;
        if (stack.isEmpty()) return;
        final var topOverlay = stack.get(stack.size() - 1);
        topOverlay.journal().record(new JournalEntry(qualifiedName, tenancyId, input, output,
                Instant.now(), dataRealism));
    }

    public DataRealism fallthroughRealism(final String qualifiedName) {
        final var stack = overlayStack;
        for (int i = stack.size() - 1; i >= 0; i--) {
            final DataRealism level = stack.get(i).config().fallthroughRealism(qualifiedName);
            if (level != null) {return level;}
        }
        return config.fallthroughRealism(qualifiedName);
    }


    static String resolveAlias(final String name) {
        return switch (name) {
            case "seq" -> "sequential";
            case "key" -> "key-lookup";
            case "rand" -> "random";
            case "replay" -> "recorded-replay";
            case "nearest" -> "nearest-match";
            default -> name;
        };
    }

    private SimulationStrategy<?, ?> createStrategy(final String qualifiedName, final String strategyName,
                                                      final SimulationCorpus corpus) {
        return switch (resolveAlias(strategyName)) {
            case "sequential" -> new SequentialStrategy<>(corpus, qualifiedName,
                    config.exhaustionPolicy(qualifiedName).orElse(ExhaustionPolicy.WRAP));
            case "key-lookup" -> {
                final KeyExtractor extractor = requireExtractor(qualifiedName);
                yield new KeyLookupStrategy<>(corpus, qualifiedName, extractor);
            }
            case "random" -> new RandomStrategy<>(corpus, qualifiedName, new Random(), null);
            case "recorded-replay" -> {
                final KeyExtractor extractor = requireExtractor(qualifiedName);
                yield new RecordedReplayStrategy<>(corpus, qualifiedName, extractor);
            }
            case "nearest-match" -> {
                final SimilarityScorer scorer = requireScorer(qualifiedName);
                final double threshold = config.threshold(qualifiedName).orElse(0.0);
                yield new NearestMatchStrategy<>(corpus, qualifiedName, scorer, threshold);
            }
            default -> throw new SimulationConfigException(
                    "Unknown strategy '" + strategyName + "' for " + qualifiedName);
        };
    }

    private SimilarityScorer<?> requireScorer(final String qualifiedName) {
        final SimilarityScorer<?> scorer = scorers.get(qualifiedName);
        if (scorer == null) {
            throw new SimulationConfigException(
                    "Strategy for " + qualifiedName + " requires a SimilarityScorer, but none registered");
        }
        return scorer;
    }

    private static final KeyExtractor<Object> IDENTITY_EXTRACTOR = String::valueOf;

    private KeyExtractor<?> requireExtractor(final String qualifiedName) {
        return extractors.getOrDefault(qualifiedName, IDENTITY_EXTRACTOR);
    }
}
