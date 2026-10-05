package io.casehub.platform.simulation.config;

import io.casehub.platform.simulation.DataRealism;
import io.casehub.platform.simulation.ExhaustionPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlSimulationConfigTest {

    @Test
    void parsesStrategyFromYaml() {
        var config = load("""
                methods:
                  test-spi.query:
                    strategy: key
                """);
        assertThat(config.strategyFor("test-spi.query")).hasValue("key");
    }

    @Test
    void parsesCaptureFromYaml() {
        var config = load("""
                methods:
                  test-spi.query:
                    capture: true
                """);
        assertThat(config.captureEnabled("test-spi.query")).isTrue();
    }

    @Test
    void captureDefaultsToFalse() {
        var config = load("""
                methods:
                  test-spi.query:
                    strategy: key
                """);
        assertThat(config.captureEnabled("test-spi.query")).isFalse();
    }

    @Test
    void parsesExhaustionPolicyFromYaml() {
        var config = load("""
                methods:
                  test-spi.query:
                    exhaustion-policy: THROW
                """);
        assertThat(config.exhaustionPolicy("test-spi.query")).hasValue(ExhaustionPolicy.THROW);
    }

    @Test
    void parsesThresholdFromYaml() {
        var config = load("""
                methods:
                  test-spi.query:
                    threshold: 0.8
                """);
        assertThat(config.threshold("test-spi.query")).hasValue(0.8);
    }

    @Test
    void parsesKeyExtractorSpec() {
        var config = load("""
                methods:
                  test-spi.query:
                    key-extractor: "field:domain"
                """);
        assertThat(config.extractorSpecs()).containsEntry("test-spi.query", "field:domain");
    }

    @Test
    void parsesScorerSpec() {
        var config = load("""
                methods:
                  test-spi.query:
                    scorer: "fields:domain:exact:1.0"
                """);
        assertThat(config.scorerSpecs()).containsEntry("test-spi.query", "fields:domain:exact:1.0");
    }

    @Test
    void returnsEmptyForUnconfiguredMethod() {
        var config = load("""
                methods:
                  test-spi.query:
                    strategy: key
                """);
        assertThat(config.strategyFor("unknown.method")).isEmpty();
        assertThat(config.captureEnabled("unknown.method")).isFalse();
        assertThat(config.exhaustionPolicy("unknown.method")).isEmpty();
        assertThat(config.threshold("unknown.method")).isEmpty();
    }

    @Test
    void parsesDefaultTenancyId() {
        var config = load("""
                default-tenancy-id: test-tenant
                methods:
                  test-spi.query:
                    strategy: key
                """);
        assertThat(config.defaultTenancyId()).hasValue("test-tenant");
    }

    @Test
    void defaultTenancyIdOverrideWins() {
        var config = loadWithOverride("""
                default-tenancy-id: yaml-tenant
                methods:
                  test-spi.query:
                    strategy: key
                """, "override-tenant");
        assertThat(config.defaultTenancyId()).hasValue("override-tenant");
    }

    @Test
    void parsesMultipleMethods() {
        var config = load("""
                methods:
                  spi-a.query:
                    strategy: key
                  spi-b.store:
                    strategy: sequential
                """);
        assertThat(config.strategyFor("spi-a.query")).hasValue("key");
        assertThat(config.strategyFor("spi-b.store")).hasValue("sequential");
    }

    // --- Corpus loading ---

    @Test
    void loadsInlineCorpusEntries() {
        var config = load("""
                default-tenancy-id: test-tenant
                methods:
                  test-spi.query:
                    strategy: key
                    corpus:
                      - key: cardiology
                        tenancy-id: hospital-a
                        input:
                          domain: cardiology
                        output: "Lab results"
                """);
        var corpus = config.loadAllCorpus();
        assertThat(corpus).containsKey("test-spi.query");
        assertThat(corpus.get("test-spi.query")).hasSize(1);

        var record = corpus.get("test-spi.query").get(0);
        assertThat(record.tenancyId()).isEqualTo("hospital-a");
        assertThat(record.key()).isEqualTo("cardiology");
        assertThat(record.output()).isEqualTo("Lab results");
    }

    @Test
    void corpusFallsBackToDefaultTenancyId() {
        var config = load("""
                default-tenancy-id: fallback-tenant
                methods:
                  test-spi.query:
                    strategy: key
                    corpus:
                      - key: neuro
                        input: "q1"
                        output: "r1"
                """);
        var record = config.loadAllCorpus().get("test-spi.query").get(0);
        assertThat(record.tenancyId()).isEqualTo("fallback-tenant");
    }

    @Test
    void corpusExplicitTenancyOverridesDefault() {
        var config = load("""
                default-tenancy-id: default
                methods:
                  test-spi.query:
                    strategy: key
                    corpus:
                      - tenancy-id: explicit
                        input: "q1"
                        output: "r1"
                """);
        var record = config.loadAllCorpus().get("test-spi.query").get(0);
        assertThat(record.tenancyId()).isEqualTo("explicit");
    }

    @Test
    void loadCorpusFromExternalFiles() {
        var config = load("""
                methods:
                  my-spi.query:
                    strategy: key
                    corpus-files:
                      - classpath:simulation/test-corpus.yaml
                """);
        var corpus = config.loadAllCorpus();
        assertThat(corpus).containsKey("my-spi.query");
        assertThat(corpus.get("my-spi.query")).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void inlineAndExternalCorpusMerge() {
        var config = load("""
                default-tenancy-id: t1
                methods:
                  my-spi.query:
                    strategy: key
                    corpus:
                      - key: inline
                        input: "inline-input"
                        output: "inline-output"
                    corpus-files:
                      - classpath:simulation/test-corpus.yaml
                """);
        var entries = config.loadAllCorpus().get("my-spi.query");
        assertThat(entries.get(0).key()).isEqualTo("inline");
        assertThat(entries.size()).isGreaterThan(1);
    }

    @Test
    void emptyMethodsBlockProducesEmptyCorpus() {
        var config = load("""
                methods:
                  test-spi.query:
                    strategy: key
                """);
        assertThat(config.loadAllCorpus()).isEmpty();
    }

    @Test
    void corpusEntryWithoutOutputThrowsAtParseTime() {
        assertThatThrownBy(() -> load("""
                methods:
                  test-spi.query:
                    strategy: key
                    corpus:
                      - input: "hello"
                """).loadAllCorpus())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output");
    }

    // --- Profiles ---

    @Test
    void profileOverridesBaseStrategy() {
        var config = load("""
                methods:
                  test-spi.query:
                    strategy: key
                profiles:
                  demo:
                    methods:
                      test-spi.query:
                        strategy: sequential
                """);
        var profile = config.resolve("demo");
        assertThat(profile).isPresent();
        assertThat(profile.get().config().strategyFor("test-spi.query"))
                .hasValue("sequential");
    }

    @Test
    void profileFallsBackToBaseForUnconfiguredMethods() {
        var config = load("""
                methods:
                  test-spi.query:
                    strategy: key
                  test-spi.store:
                    strategy: sequential
                profiles:
                  demo:
                    methods:
                      test-spi.query:
                        strategy: random
                """);
        var profile = config.resolve("demo");
        assertThat(profile).isPresent();
        assertThat(profile.get().config().strategyFor("test-spi.store"))
                .hasValue("sequential");
    }

    @Test
    void profileCanTurnCaptureOff() {
        var config = load("""
                methods:
                  test-spi.query:
                    strategy: key
                    capture: true
                profiles:
                  no-capture:
                    methods:
                      test-spi.query:
                        capture: false
                """);
        assertThat(config.captureEnabled("test-spi.query")).isTrue();
        var profile = config.resolve("no-capture");
        assertThat(profile).isPresent();
        assertThat(profile.get().config().captureEnabled("test-spi.query")).isFalse();
    }

    @Test
    void profileOmittingCaptureFallsBackToBase() {
        var config = load("""
                methods:
                  test-spi.query:
                    strategy: key
                    capture: true
                profiles:
                  other:
                    methods:
                      test-spi.query:
                        strategy: seq
                """);
        var profile = config.resolve("other");
        assertThat(profile).isPresent();
        assertThat(profile.get().config().captureEnabled("test-spi.query")).isTrue();
    }

    @Test
    void resolveUnknownProfileReturnsEmpty() {
        var config = load("""
                methods:
                  test-spi.query:
                    strategy: key
                """);
        assertThat(config.resolve("nonexistent")).isEmpty();
    }

    @Test
    void profileNamesReturnsAllProfiles() {
        var config = load("""
                methods:
                  test-spi.query:
                    strategy: key
                profiles:
                  demo:
                    methods:
                      test-spi.query:
                        strategy: sequential
                  staging:
                    methods:
                      test-spi.query:
                        strategy: random
                """);
        assertThat(config.profileNames()).containsExactlyInAnyOrder("demo", "staging");
    }

    @Test
    void loadAllCorpusWithProfileMergesEntries() {
        var config = load("""
                default-tenancy-id: t1
                methods:
                  test-spi.query:
                    strategy: key
                    corpus:
                      - key: base
                        input: "base-input"
                        output: "base-output"
                profiles:
                  demo:
                    methods:
                      test-spi.query:
                        strategy: sequential
                        corpus:
                          - input: "demo-input"
                            output: "demo-output"
                """);
        var corpus = config.loadAllCorpus("demo");
        var entries = corpus.get("test-spi.query");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).key()).isEqualTo("base");
        assertThat(entries.get(1).output()).isEqualTo("demo-output");
    }

    @Test
    void loadAllCorpusWithProfileLevelCorpusFiles() {
        var config = load("""
                default-tenancy-id: t1
                methods:
                  my-spi.query:
                    strategy: key
                profiles:
                  demo:
                    methods:
                      my-spi.query:
                        strategy: sequential
                    corpus-files:
                      - classpath:simulation/extra-corpus.yaml
                """);
        var corpus = config.loadAllCorpus("demo");
        assertThat(corpus).containsKey("my-spi.query");
    }

    // --- Edge cases ---

    @Test
    void emptyInputStreamProducesNoOpConfig() {
        var config = new YamlSimulationConfig(
                new ByteArrayInputStream(new byte[0]));
        assertThat(config.strategyFor("any")).isEmpty();
        assertThat(config.loadAllCorpus()).isEmpty();
        assertThat(config.profileNames()).isEmpty();
    }

    @Test
    void unknownTopLevelKeyLogsWarning() {
        var handler = new java.util.logging.Handler() {
            final java.util.List<String> warnings = new java.util.ArrayList<>();
            @Override public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel() == java.util.logging.Level.WARNING) warnings.add(r.getMessage());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var logger = java.util.logging.Logger.getLogger(YamlSimulationConfig.class.getName());
        logger.addHandler(handler);
        try {
            load("""
                    methds:
                      test-spi.query:
                        strategy: key
                    """);
            assertThat(handler.warnings).anyMatch(w -> w.contains("methds"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void malformedYamlThrowsUncheckedIOException() {
        assertThatThrownBy(() -> load("not: [valid: yaml: {{"))
                .isInstanceOf(UncheckedIOException.class);
    }

    // --- Helpers ---


    @Test
    void speedParsedFromYaml() {
        var config = load("""
                          speed: 5.0
                          methods:
                            spi.query:
                              strategy: seq
                          """);
        assertThat(config.speed()).isEqualTo(5.0);
    }

    @Test
    void speedDefaultsToOneWhenAbsent() {
        var config = load("""
                          methods:
                            spi.query:
                              strategy: seq
                          """);
        assertThat(config.speed()).isEqualTo(1.0);
    }

    @Test
    void speedRejectsNonPositive() {
        assertThatThrownBy(() -> load("""
                                      speed: 0
                                      methods:
                                        spi.query:
                                          strategy: seq
                                      """))
                .isInstanceOf(io.casehub.platform.simulation.SimulationConfigException.class);
    }


    @Test
    void parsesFallthroughRealismFromYaml() {
        var config = load("""
                          methods:
                            test-spi.resolve:
                              fallthrough-realism: STRUCTURALLY_VALID
                          """);
        assertThat(config.fallthroughRealism("test-spi.resolve"))
                .isEqualTo(DataRealism.STRUCTURALLY_VALID);
    }

    @Test
    void fallthroughRealismReturnsNullForUnconfiguredMethod() {
        var config = load("""
                          methods:
                            test-spi.resolve:
                              fallthrough-realism: STRUCTURALLY_VALID
                          """);
        assertThat(config.fallthroughRealism("unknown.method")).isNull();
    }

    @Test
    void profileOverridesFallthroughRealism() {
        var config = load("""
                          methods:
                            test-spi.resolve:
                              fallthrough-realism: STRUCTURALLY_VALID
                          profiles:
                            demo:
                              methods:
                                test-spi.resolve:
                                  fallthrough-realism: DOMAIN_PLAUSIBLE
                          """);
        var profile = config.resolve("demo");
        assertThat(profile).isPresent();
        assertThat(profile.get().config().fallthroughRealism("test-spi.resolve"))
                .isEqualTo(DataRealism.DOMAIN_PLAUSIBLE);
    }

    @Test
    void profileFallsBackToBaseFallthroughRealism() {
        var config = load("""
                          methods:
                            test-spi.resolve:
                              fallthrough-realism: STRUCTURALLY_VALID
                          profiles:
                            demo:
                              methods:
                                test-spi.resolve:
                                  strategy: seq
                          """);
        var profile = config.resolve("demo");
        assertThat(profile).isPresent();
        assertThat(profile.get().config().fallthroughRealism("test-spi.resolve"))
                .isEqualTo(DataRealism.STRUCTURALLY_VALID);
    }

    private YamlSimulationConfig load(String yaml) {
        return new YamlSimulationConfig(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private YamlSimulationConfig loadWithOverride(String yaml, String tenancyOverride) {
        return new YamlSimulationConfig(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                tenancyOverride);
    }
}
