# Decisions — DataRealism E2E Verification (#517)

## D1: Include fallthrough-realism implementation

**Choice:** Include both the fallthrough-realism mechanism and the E2E test in this issue
**Alternatives:**
- Test current behavior only — tests would verify null for all delegate calls, deferring interpretive tagging to a separate issue. Leaves acceptance criteria unmet.
- Test with TODO assertions — test harness exists but STRUCTURALLY_VALID assertion is @Disabled. Proves structure but not behavior.
**Rationale:** The decorator processor currently records null for all delegate fallthrough, but the design spec (connectors#138) expects STRUCTURALLY_VALID for interpretive ref fallthrough. Including the implementation keeps the issue self-contained and makes the E2E test meaningful.
**Trade-offs:** Scope is larger than pure testing, but without the mechanism the test can't verify the core acceptance criteria.
**Sources:** SimulationDecoratorProcessor.java:401-409 (delegate path hardcodes null), connectors#138 design spec (interpretive fallthrough → STRUCTURALLY_VALID)
**Exploration:** quick
**Status:** captured

## D2: Test fixture in platform, not cross-repo

**Choice:** Create a minimal test SPI with mixed deterministic/interpretive methods in the platform repo
**Alternatives:**
- connectors repo — natural home for CommercePlatform tests but separates test from implementation changes
- Add commerce-spi/ref as test deps — uses real connector but creates cross-repo build dependency
**Rationale:** Implementation changes (decorator processor, runtime, config) are all in platform. Tests should live alongside the code they verify. Platform has zero dependencies on connectors today and shouldn't acquire one.
**Trade-offs:** Test uses a synthetic SPI rather than the real CommercePlatform exemplar. CommercePlatform can run its own E2E in connectors later.
**Sources:** platform pom.xml (no connectors dependencies), issue #517 (names CommercePlatform as exemplar but acceptance criteria are about the three DataRealism paths)
**Exploration:** quick
**Status:** captured

## D3: simulation.yaml config for fallthrough-realism

**Choice:** Add `fallthrough-realism` as a per-method field in simulation.yaml, parsed through the existing SimulationConfig pipeline
**Alternatives:**
- @SimulationEligible annotation — compile-time classification via annotation metadata. Rigid, requires recompilation to change.
- Separate @Interpretive annotation — most explicit but adds annotation surface area.
**Rationale:** Keeps classification in the same place as strategy config. No annotation changes needed. Runtime-configurable via profiles/overlays. Consistent with existing per-method MethodConfig pattern.
**Trade-offs:** Classification is runtime config rather than compile-time metadata — a deployment with wrong simulation.yaml could misclassify methods. Acceptable because simulation config is already trusted for strategy selection.
**Sources:** YamlSimulationConfig.java (MethodConfig record, parseMethodConfig), simulation.schema.json
**Exploration:** quick
**Status:** captured

## D4: Per-method fallthrough-realism through config pipeline (Approach A)

**Choice:** SimulationConfig-level per-method fallthrough-realism — new field parsed through existing pipeline, consulted at runtime in delegate path
**Alternatives:**
- Implicit from strategy presence — auto-tag STRUCTURALLY_VALID when strategy exists but can't resolve. Zero config but hardcoded level and can't handle interpretive methods with no strategy configured.
**Rationale:** Explicit, handles all cases (with or without strategy), minimal touchpoints (one field through existing pipeline, one-line APT change). The delegate path in generated decorators covers both "no strategy" and "strategy can't resolve" — both use the configured fallthrough level.
**Trade-offs:** Requires declaring fallthrough-realism for every interpretive method in simulation.yaml. Low burden since it's alongside existing strategy config.
**Sources:** SimulationRuntime.java:52-66 (strategyFor overlay walk pattern), SimulationDecoratorProcessor.java:389-416 (delegate path code generation)
**Exploration:** quick
**Depends on:** D3 (simulation.yaml config mechanism)
**Status:** captured

## D5: E2E test uses existing generated decorator

**Choice:** Use an existing generated decorator from platform-simulation-core (e.g., SimulatedEndpointRegistry) with a mock delegate and test simulation.yaml
**Alternatives:**
- New test SPI with APT — create a @SimulationEligible test interface, configure APT to process test sources. Full pipeline test but significantly more module setup.
**Rationale:** platform-simulation-core already has 11 generated decorators from platform-api SPIs. Configuring different methods with strategies, fallthrough-realism, or neither tests all three DataRealism paths without creating new SPIs. Tests real generated code.
**Trade-offs:** Test is coupled to a specific platform-api SPI's method signatures. Acceptable — these are stable foundational interfaces.
**Sources:** platform-simulation-core (generated decorators for 11 SPIs), META-INF/simulation-eligible.txt
**Exploration:** quick
**Depends on:** D2 (test fixture in platform)
**Status:** captured
