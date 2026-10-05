package io.casehub.platform.simulation;

import io.casehub.platform.simulation.strategy.KeyLookupStrategy;
import io.casehub.platform.simulation.strategy.NearestMatchStrategy;
import io.casehub.platform.simulation.strategy.RandomStrategy;
import io.casehub.platform.simulation.strategy.RecordedReplayStrategy;
import io.casehub.platform.simulation.strategy.SequentialStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationRuntimeTest {

    private static final String QN = "test-spi.query";

    // --- strategyFor ---

    @Test
    void strategyForReturnsEmptyWhenNoConfig() {
        final var config = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());

        assertThat(runtime.<String, String>strategyFor(QN)).isEmpty();
    }

    @Test
    void strategyForReturnsSequentialStrategy() {
        final var config = stubConfig(Optional.of("sequential"), false, Optional.empty());
        final var corpus = new NoOpSimulationCorpus<>();
        final var runtime = new SimulationRuntime(config, corpus);

        final var strategy = runtime.strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get()).isInstanceOf(SequentialStrategy.class);
    }

    @Test
    void strategyForReturnsRandomStrategy() {
        final var config = stubConfig(Optional.of("random"), false, Optional.empty());
        final var corpus = new NoOpSimulationCorpus<>();
        final var runtime = new SimulationRuntime(config, corpus);

        final var strategy = runtime.strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get()).isInstanceOf(RandomStrategy.class);
    }

    @Test
    void strategyForReturnsKeyLookupWhenExtractorRegistered() {
        final var config = stubConfig(Optional.of("key-lookup"), false, Optional.empty());
        final var corpus = new NoOpSimulationCorpus<>();
        final var runtime = new SimulationRuntime(config, corpus);
        runtime.registerExtractor(QN, (String input) -> input.toUpperCase());

        final var strategy = runtime.strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get()).isInstanceOf(KeyLookupStrategy.class);
    }

    @Test
    void keyLookupUsesIdentityExtractorWhenNoneRegistered() {
        final var config = stubConfig(Optional.of("key-lookup"), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());

        assertThat(runtime.strategyFor(QN)).isPresent();
    }

    @Test
    void strategyForReturnsRecordedReplayWhenExtractorRegistered() {
        final var config = stubConfig(Optional.of("recorded-replay"), false, Optional.empty());
        final var corpus = new NoOpSimulationCorpus<>();
        final var runtime = new SimulationRuntime(config, corpus);
        runtime.registerExtractor(QN, (String input) -> input);

        final var strategy = runtime.strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get()).isInstanceOf(RecordedReplayStrategy.class);
    }

    // --- strategy aliases ---

    @Test
    void aliasSeqResolvesToSequential() {
        final var config = stubConfig(Optional.of("seq"), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());
        assertThat(runtime.strategyFor(QN).get()).isInstanceOf(SequentialStrategy.class);
    }

    @Test
    void aliasKeyResolvesToKeyLookup() {
        final var config = stubConfig(Optional.of("key"), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());
        runtime.registerExtractor(QN, (String s) -> s);
        assertThat(runtime.strategyFor(QN).get()).isInstanceOf(KeyLookupStrategy.class);
    }

    @Test
    void aliasRandResolvesToRandom() {
        final var config = stubConfig(Optional.of("rand"), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());
        assertThat(runtime.strategyFor(QN).get()).isInstanceOf(RandomStrategy.class);
    }

    @Test
    void aliasReplayResolvesToRecordedReplay() {
        final var config = stubConfig(Optional.of("replay"), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());
        runtime.registerExtractor(QN, (String s) -> s);
        assertThat(runtime.strategyFor(QN).get()).isInstanceOf(RecordedReplayStrategy.class);
    }

    @Test
    void aliasNearestResolvesToNearestMatch() {
        final var config = stubConfig(Optional.of("nearest"), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());
        runtime.registerScorer(QN, (String a, String b) -> 1.0);
        assertThat(runtime.strategyFor(QN).get()).isInstanceOf(NearestMatchStrategy.class);
    }

    @Test
    void strategyForThrowsOnUnknownStrategyName() {
        final var config = stubConfig(Optional.of("nonexistent"), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());

        assertThatThrownBy(() -> runtime.strategyFor(QN))
                .isInstanceOf(SimulationConfigException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void strategyIsCachedAcrossCalls() {
        final var config = stubConfig(Optional.of("sequential"), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());

        final var first = runtime.strategyFor(QN);
        final var second = runtime.strategyFor(QN);
        assertThat(first.get()).isSameAs(second.get());
    }

    // --- captureEnabled ---

    @Test
    void captureEnabledDelegatesToConfig() {
        final var config = stubConfig(Optional.empty(), true, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());

        assertThat(runtime.captureEnabled(QN)).isTrue();
    }

    @Test
    void captureDisabledWhenConfigSaysNo() {
        final var config = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());

        assertThat(runtime.captureEnabled(QN)).isFalse();
    }

    // --- capture ---

    @Test
    void captureDelegatesToCorpus() {
        final var config = stubConfig(Optional.empty(), true, Optional.empty());
        final var corpus = new TestCorpus();
        final var runtime = new SimulationRuntime(config, corpus);

        runtime.capture(QN, "tenant-1", "input-val", "output-val");

        assertThat(corpus.lastQualifiedName).isEqualTo(QN);
        assertThat(corpus.lastTenancyId).isEqualTo("tenant-1");
    }

    @Test
    void captureWithKeyDelegatesToCorpus() {
        final var config = stubConfig(Optional.empty(), true, Optional.empty());
        final var corpus = new TestCorpus();
        final var runtime = new SimulationRuntime(config, corpus);

        runtime.capture(QN, "tenant-1", "my-key", "input-val", "output-val");

        assertThat(corpus.lastKey).isEqualTo("my-key");
    }

    // --- exhaustionPolicy ---

    @Test
    void sequentialStrategyUsesConfiguredExhaustionPolicy() {
        final var config = stubConfig(Optional.of("sequential"), false, Optional.of(ExhaustionPolicy.THROW));
        final var corpus = new TestCorpus();
        corpus.seed(QN, List.of(new InvocationRecord<>("t1", "k", "in", "out", Instant.now())));
        final var runtime = new SimulationRuntime(config, corpus);

        final var strategy = runtime.strategyFor(QN);
        assertThat(strategy).isPresent();
        strategy.get().resolve("any");

        assertThatThrownBy(() -> strategy.get().resolve("any"))
                .isInstanceOf(SimulationExhaustedException.class);
    }

    // --- nearest-match ---

    @Test
    void strategyForReturnsNearestMatchWhenScorerRegistered() {
        final var config = stubConfig(Optional.of("nearest-match"), false, Optional.empty());
        final var corpus = new NoOpSimulationCorpus<>();
        final var runtime = new SimulationRuntime(config, corpus);
        runtime.registerScorer(QN, (SimilarityScorer<String>) (q, c) -> q.equals(c) ? 1.0 : 0.0);

        final var strategy = runtime.strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get()).isInstanceOf(NearestMatchStrategy.class);
    }

    @Test
    void strategyForThrowsWhenNearestMatchWithoutScorer() {
        final var config = stubConfig(Optional.of("nearest-match"), false, Optional.empty());
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());

        assertThatThrownBy(() -> runtime.strategyFor(QN))
                .isInstanceOf(SimulationConfigException.class)
                .hasMessageContaining("SimilarityScorer");
    }

    @Test
    void nearestMatchStrategyResolvesFromRegisteredScorer() {
        final var config = stubConfig(Optional.of("nearest-match"), false, Optional.empty());
        final var corpus = new TestCorpus();
        corpus.seed(QN, List.of(
                new InvocationRecord<>("t1", null, "hello", "world", Instant.now())));
        final var runtime = new SimulationRuntime(config, corpus);
        runtime.registerScorer(QN, (SimilarityScorer<String>) (q, c) -> q.equals(c) ? 1.0 : 0.0);

        final Optional<SimulationStrategy<String, String>> strategy = runtime.strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get().resolve("hello")).isEqualTo("world");
    }

    // --- overlay stack ---

    @Test
    void pushOverlayMakesStrategyResolveFromOverlay() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());
        assertThat(runtime.<String, String>strategyFor(QN)).isEmpty();

        final var overlayCorpus = new TestCorpus();
        overlayCorpus.seed(QN, List.of(new InvocationRecord<>("t1", null, "in", "out", Instant.now())));
        final var overlay = runtime.pushOverlay(
                MapSimulationConfig.of(Map.of(QN, "sequential")), overlayCorpus);

        assertThat(runtime.<String, String>strategyFor(QN)).isPresent();
        assertThat(runtime.hasActiveOverlay()).isTrue();
    }

    @Test
    void overlayStrategyUsesOverlayCorpus() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var baseCorpus = new TestCorpus();
        baseCorpus.seed(QN, List.of(new InvocationRecord<>("t1", null, "in", "base-value", Instant.now())));
        final var runtime = new SimulationRuntime(baseConfig, baseCorpus);

        final var overlayCorpus = new TestCorpus();
        overlayCorpus.seed(QN, List.of(new InvocationRecord<>("t1", null, "in", "overlay-value", Instant.now())));
        runtime.pushOverlay(MapSimulationConfig.of(Map.of(QN, "sequential")), overlayCorpus);

        final var strategy = runtime.<String, String>strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get().resolve("in")).isEqualTo("overlay-value");
    }

    @Test
    void popOverlayRestoresBaseResolution() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());

        final var overlay = runtime.pushOverlay(MapSimulationConfig.of(Map.of(QN, "sequential")));

        runtime.popOverlay(overlay);
        assertThat(runtime.<String, String>strategyFor(QN)).isEmpty();
        assertThat(runtime.hasActiveOverlay()).isFalse();
    }

    @Test
    void multipleOverlaysResolveTopDown() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());

        final var corpus1 = new TestCorpus();
        corpus1.seed(QN, List.of(new InvocationRecord<>("t1", null, "in", "first", Instant.now())));
        runtime.pushOverlay(MapSimulationConfig.of(Map.of(QN, "sequential")), corpus1);

        final var corpus2 = new TestCorpus();
        corpus2.seed(QN, List.of(new InvocationRecord<>("t1", null, "in", "second", Instant.now())));
        runtime.pushOverlay(MapSimulationConfig.of(Map.of(QN, "sequential")), corpus2);

        final var strategy = runtime.<String, String>strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get().resolve("in")).isEqualTo("second");
    }

    @Test
    void popAllClearsEntireStack() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());

        runtime.pushOverlay(MapSimulationConfig.of(Map.of(QN, "sequential")));
        runtime.pushOverlay(MapSimulationConfig.of(Map.of("other.method", "random")));

        runtime.popAll();
        assertThat(runtime.hasActiveOverlay()).isFalse();
        assertThat(runtime.<String, String>strategyFor(QN)).isEmpty();
    }

    @Test
    void journalReturnsOverlayJournal() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());
        final var overlay = runtime.pushOverlay(MapSimulationConfig.of(Map.of()));

        runtime.recordJournal(QN, "tenant-1", "input", "output", DataRealism.DOMAIN_PLAUSIBLE);

        final var journal = runtime.journal(overlay);
        assertThat(journal).hasSize(1);
        assertThat(journal.get(0).dataRealism()).isEqualTo(DataRealism.DOMAIN_PLAUSIBLE);
    }

    @Test
    void recordJournalNoOpWhenNoOverlay() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());

        runtime.recordJournal(QN, "tenant-1", "input", "output", null);
    }

    @Test
    void popOverlayThrowsForUnknownOverlay() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());
        final var overlay = new SimulationOverlay(MapSimulationConfig.of(Map.of()), new NoOpSimulationCorpus<>());

        assertThatThrownBy(() -> runtime.popOverlay(overlay))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlayDoesNotAffectUnrelatedQualifiedNames() {
        final var baseConfig = stubConfig(Optional.of("sequential"), false, Optional.empty());
        final var baseCorpus = new TestCorpus();
        baseCorpus.seed(QN, List.of(new InvocationRecord<>("t1", null, "in", "base", Instant.now())));
        final var runtime = new SimulationRuntime(baseConfig, baseCorpus);

        runtime.pushOverlay(MapSimulationConfig.of(Map.of("other.method", "sequential")));

        final var strategy = runtime.<String, String>strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get().resolve("in")).isEqualTo("base");
    }

    // --- auto-detect identity extractor ---

    @Test
    void keyLookupFallsBackToIdentityExtractorWhenNoneRegistered() {
        final var config = stubConfig(Optional.of("key-lookup"), false, Optional.empty());
        final var corpus = new TestCorpus();
        corpus.seed(QN, List.of(new InvocationRecord<>("t1", "patient-123", "patient-123", "result", Instant.now())));
        final var runtime = new SimulationRuntime(config, corpus);

        final var strategy = runtime.<String, String>strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get().resolve("patient-123")).isEqualTo("result");
    }

    @Test
    void recordedReplayFallsBackToIdentityExtractorWhenNoneRegistered() {
        final var config = stubConfig(Optional.of("recorded-replay"), false, Optional.empty());
        final var corpus = new TestCorpus();
        corpus.seed(QN, List.of(new InvocationRecord<>("t1", "key-1", "input-1", "result-1", Instant.now())));
        final var runtime = new SimulationRuntime(config, corpus);

        final var strategy = runtime.<String, String>strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get().resolve("key-1")).isEqualTo("result-1");
    }

    // --- apply(CorpusSeed) ---

    @Test
    void applySeedsCorpusAndRegistersExtractor() {
        final var config = stubConfig(Optional.of("key-lookup"), false, Optional.empty());
        final var corpus = new TestCorpus();
        final var runtime = new SimulationRuntime(config, corpus);

        final var seed = new CorpusSeed<String, String>("test-spi.query", "tenant-1")
                .withKeyExtractor(String::toLowerCase);
        seed.add("alice", "Alice", "Hello Alice!");
        seed.add("bob", "Bob", "Hello Bob!");

        runtime.apply(seed);

        final var strategy = runtime.<String, String>strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get().resolve("ALICE")).isEqualTo("Hello Alice!");
        assertThat(strategy.get().resolve("Bob")).isEqualTo("Hello Bob!");
    }

    @Test
    void applyWithoutExtractorSeedsCorpusOnly() {
        final var config = stubConfig(Optional.of("sequential"), false, Optional.empty());
        final var corpus = new TestCorpus();
        final var runtime = new SimulationRuntime(config, corpus);

        final var seed = new CorpusSeed<String, String>("test-spi.query", "tenant-1");
        seed.add("Alice", "Hello Alice!");
        seed.add("Bob", "Hello Bob!");

        runtime.apply(seed);

        final var strategy = runtime.<String, String>strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get().resolve("anyone")).isEqualTo("Hello Alice!");
        assertThat(strategy.get().resolve("anyone")).isEqualTo("Hello Bob!");
    }

    // --- helpers ---


// --- pushProfile ---

    @Test
    void pushProfileActivatesNamedProfile() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime    = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());

        final var profileCorpus = new TestCorpus();
        profileCorpus.seed(QN, List.of(new InvocationRecord<>("t1", null, "in", "profile-value", Instant.now())));
        final var profileConfig = MapSimulationConfig.of(Map.of(QN, "sequential"));
        final var profile       = new SimulationProfile(profileConfig, profileCorpus);

        runtime.setProfileSource(name -> "test-profile".equals(name) ? Optional.of(profile) : Optional.empty());

        final var overlay  = runtime.pushProfile("test-profile");
        final var strategy = runtime.<String, String>strategyFor(QN);
        assertThat(strategy).isPresent();
        assertThat(strategy.get().resolve("in")).isEqualTo("profile-value");

        runtime.popOverlay(overlay);
        assertThat(runtime.<String, String>strategyFor(QN)).isEmpty();
    }

    @Test
    void pushProfileThrowsForUnknownProfile() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime    = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());
        runtime.setProfileSource(name -> Optional.empty());

        assertThatThrownBy(() -> runtime.pushProfile("nonexistent"))
                .isInstanceOf(SimulationConfigException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void pushProfileThrowsWhenNoProfileSource() {
        final var baseConfig = stubConfig(Optional.empty(), false, Optional.empty());
        final var runtime    = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());

        assertThatThrownBy(() -> runtime.pushProfile("any"))
                .isInstanceOf(SimulationConfigException.class)
                .hasMessageContaining("ProfileSource");
    }


    @Test
    void fallthroughRealismDefaultsToNull() {
        final var config = stubConfig(Optional.empty(), false, Optional.empty());
        assertThat(config.fallthroughRealism("any.method")).isNull();
    }

    @Test
    void mapSimulationConfigSupportsFallthroughRealism() {
        final var config = MapSimulationConfig.builder()
                                              .fallthroughRealism(QN, DataRealism.STRUCTURALLY_VALID)
                                              .build();
        assertThat(config.fallthroughRealism(QN))
                .isEqualTo(DataRealism.STRUCTURALLY_VALID);
        assertThat(config.fallthroughRealism("other.method")).isNull();
    }

    @Test
    void fallthroughRealismReturnsConfiguredLevel() {
        final var config = MapSimulationConfig.builder()
                                              .fallthroughRealism(QN, DataRealism.STRUCTURALLY_VALID)
                                              .build();
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());
        assertThat(runtime.fallthroughRealism(QN)).isEqualTo(DataRealism.STRUCTURALLY_VALID);
    }

    @Test
    void fallthroughRealismReturnsNullForUnconfiguredMethod() {
        final var config = MapSimulationConfig.builder()
                                              .fallthroughRealism(QN, DataRealism.STRUCTURALLY_VALID)
                                              .build();
        final var runtime = new SimulationRuntime(config, new NoOpSimulationCorpus<>());
        assertThat(runtime.fallthroughRealism("other.method")).isNull();
    }

    @Test
    void overlayOverridesBaseFallthroughRealism() {
        final var baseConfig = MapSimulationConfig.builder()
                                                  .fallthroughRealism(QN, DataRealism.STRUCTURALLY_VALID)
                                                  .build();
        final var runtime = new SimulationRuntime(baseConfig, new NoOpSimulationCorpus<>());

        final var overlayConfig = MapSimulationConfig.builder()
                                                     .fallthroughRealism(QN, DataRealism.DOMAIN_PLAUSIBLE)
                                                     .build();
        final var overlay = runtime.pushOverlay(overlayConfig);

        assertThat(runtime.fallthroughRealism(QN)).isEqualTo(DataRealism.DOMAIN_PLAUSIBLE);

        runtime.popOverlay(overlay);
        assertThat(runtime.fallthroughRealism(QN)).isEqualTo(DataRealism.STRUCTURALLY_VALID);
    }


    private static SimulationConfig stubConfig(final Optional<String> strategy,
                                               final boolean capture,
                                               final Optional<ExhaustionPolicy> exhaustion) {
        return new SimulationConfig() {
            @Override
            public Optional<String> strategyFor(final String qualifiedName) {
                return strategy;
            }

            @Override
            public boolean captureEnabled(final String qualifiedName) {
                return capture;
            }

            @Override
            public Optional<ExhaustionPolicy> exhaustionPolicy(final String qualifiedName) {
                return exhaustion;
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static class TestCorpus implements SimulationCorpus {

        String lastQualifiedName;
        String lastTenancyId;
        String lastKey;
        private final java.util.List records = new java.util.ArrayList();

        @Override
        public Optional lookupByKey(final String qualifiedName, final String key) {
            return records.stream()
                    .filter(r -> key.equals(((InvocationRecord) r).key()))
                    .map(r -> ((InvocationRecord) r).output())
                    .findFirst();
        }

        @Override
        public Optional lookupByIndex(final String qualifiedName, final int index) {
            if (index < 0 || index >= records.size()) return Optional.empty();
            return Optional.of(((InvocationRecord) records.get(index)).output());
        }

        @Override
        public java.util.List list(final String qualifiedName) {
            return records;
        }

        @Override
        public java.util.List listByTenant(final String qualifiedName, final String tenancyId) {
            return records;
        }

        @Override
        public void record(final String qualifiedName, final String tenancyId, final Object input, final Object output) {
            lastQualifiedName = qualifiedName;
            lastTenancyId = tenancyId;
            records.add(new InvocationRecord<>(tenancyId, null, input, output, Instant.now()));
        }

        @Override
        public void record(final String qualifiedName, final String tenancyId, final String key, final Object input, final Object output) {
            lastQualifiedName = qualifiedName;
            lastTenancyId = tenancyId;
            lastKey = key;
            records.add(new InvocationRecord<>(tenancyId, key, input, output, Instant.now()));
        }

        @Override
        public void seed(final String qualifiedName, final java.util.List newRecords) {
            records.addAll(newRecords);
        }

        @Override
        public void clear(final String qualifiedName) {
            records.clear();
        }

        @Override
        public int size(final String qualifiedName) {
            return records.size();
        }
    }
}
