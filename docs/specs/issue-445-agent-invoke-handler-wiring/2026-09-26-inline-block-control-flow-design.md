# Inline Block-Level Control Flow

**Repo:** casehubio/platform
**Depends on:** #386 (runtime orchestration primitives — decorator evaluation order, parallel:)
**Revises:** #386 D3 (drops when-pairs-only for branching, adds if/else + match)

## Problem

Control flow constructs (`loop`, `forEach`, `if`, `retry`, `timeout`, etc.) only work as decorators on single steps. To apply control flow to multiple steps, the only option is module extraction — creating a separate YAML file and importing it. For 2-3 steps that are only used in one place, this is excessive ceremony.

Every real programming language and workflow DSL provides inline grouping. The platform's own `parallel:` keyword already groups steps as a structural keyword. The gap is that no other control flow can do the same.

## Scope

**In scope:**
- `block:` structural keyword for inline step grouping
- `if` replaces `when` for imperative guards (decorator + structural `if/then/else`)
- `match/cases` for N-way pattern matching
- Uniform decorator-on-step-type model
- `when` → `if` rename in yaml-core (YamlImport, ForEachAdapter, step decorators)

**Out of scope:**
- `try/catch/finally` structured error handling (future extension)
- `select` CSP-style channel select (future extension)
- Reactive `when` rules-engine construct (future design)
- Runtime implementation of decorators (issue-386 consuming-module scope)

## Design

### Step Type System

Every entry in a `steps:` list is a step. A step has a **type** (what it does) and optional **decorators** (how it does it). The type system:

| Step type | Key | Contains | Purpose |
|-----------|-----|----------|---------|
| Plugin action | plugin name | params map | Execute one action |
| Invoke | `invoke:` | binding spec | Execute one binding |
| Block | `block:` | step list | Execute N steps sequentially |
| If/else | `if:` + `then:` | 2 step lists | Branch on condition |
| Match | `match:` + `cases:` | N step lists | Branch on pattern |
| Parallel | `parallel:` | step list | Execute N steps concurrently (runtime semantics from issue-386) |

All step types occupy position 10 in the decorator evaluation order. All decorators (positions 1-9, 11-13) apply uniformly to all step types.

### 1. `block:` — Compound Step

Groups 1..N steps into a single step that decorators can wrap.

```yaml
- block:
    - rest-call: { url: ${endpoint}/status }
    - assert: { condition: ${result.rest-call.status} == "ok" }
  loop: { count: 3, until: ${status} == "ok" }
  timeout: 30s
```

**Semantics:**
- Steps inside execute sequentially (same as top-level steps)
- The block is a single step — decorators wrap the entire group
- Decorator composition is flat — stack decorators on the block, no nesting required
- Blocks nest naturally (a block can contain blocks)

**Decorator stacking:**
```yaml
- block:
    - validate: ...
    - process: ...
    - confirm: ...
  if: ${should-run}
  forEach: { group: items }
  loop: { count: 3, until: ${confirmed} }
  timeout: 60s
  retry: { max: 2, backoff: exponential }
  on-error: skip
```

Evaluation order (outside-in): `if` → `forEach` → `loop` → `on-error` → `timeout` → `retry` → block execution. Identical to the decorator evaluation order for a single step.

**Variable scoping:**
- Block-internal step results are available to subsequent steps within the block (`${result.step-name.*}`)
- Loop `until` and `forEach` expressions reference results from the block's last iteration
- After the block completes, results from the final execution are available to subsequent steps outside the block

**Relationship to modules:** `block:` is for inline grouping; modules are for reuse, parameterization, and distribution. They are complementary:

| Concern | `block:` | Module import |
|---------|----------|---------------|
| Reuse | Single site | Multiple playbooks |
| Parameters | No (uses enclosing scope) | Typed parameters |
| Import chain | No | Own imports |
| Packaging | Inline | Distributable file |
| Overhead | 1 line | Separate file + import |

### 2. `if` — Imperative Conditional

Replaces `when` in the imperative step vocabulary. Two forms:

#### Decorator form (guard)

Skip a step or block if the condition is false.

```yaml
- rest-call: { url: ${endpoint} }
  if: ${should-call}

- block:
    - step-a: ...
    - step-b: ...
  if: ${should-run}
```

Evaluated once at position 1 in the decorator order. If false, the entire step (including all other decorators) is skipped.

#### Structural form (branching)

2-way branching over step lists.

```yaml
- if: ${risk} == 'HIGH'
  then:
    - escalate: ...
    - notify-team: ...
  else:
    - proceed: ...
```

**Semantics:**
- `if:` — condition expression (evaluated via ExpressionEngine)
- `then:` — step list executed when condition is true
- `else:` — step list executed when condition is false (optional)
- `if/then` without `else` is valid — equivalent to `block:` + `if` guard but reads more naturally for conditional sequences

**Disambiguation rule:** Presence of `then:` key → structural. No `then:` key → decorator. Simple, unambiguous, no parser lookahead needed.

**Decorators on if/else:**
```yaml
- if: ${retry-needed}
  then:
    - retry-operation: ...
  else:
    - skip-retry: ...
  timeout: 30s
  on-error: skip
```

The `timeout` wraps the entire if/else — whichever branch executes must complete within 30s.

**Guard-on-conditional pattern:** When you need to guard an if/else (`if precondition, then if condition do A else do B`), YAML's duplicate-key constraint prevents putting `if` as both decorator and structural keyword on the same entry. Use a `block:` wrapper:

```yaml
- block:
    - if: ${risk} == 'HIGH'
      then:
        - escalate: ...
      else:
        - proceed: ...
  if: ${feature-enabled}
```

The `if` decorator guards the block; the `if/else` inside branches.

### 3. `match/cases` — Pattern Matching

N-way branching with value matching, structural matching, and guard conditions.

```yaml
- match: ${event}
  cases:
    - pattern: { type: "trade" }
      guard: ${match.amount} > 1000000
      steps:
        - escalate: ...
        - notify-compliance: ...

    - pattern: { type: "trade" }
      steps:
        - process-normal: ...

    - pattern: { type: "settlement" }
      steps:
        - settle: ...

    - default:
        - log: { message: "unhandled event" }
```

**Case structure:**
- `pattern:` — the match criterion. Scalar → value equality. Map → top-level structural subset match (all specified top-level fields must be present with equal values; extra top-level fields in the scrutinee are ignored; nested value comparison is exact via `Objects.equals`)
- `guard:` — optional boolean expression evaluated after pattern matches
- `steps:` — step list for this case
- `default:` — catch-all case (step list directly as value, no `pattern:`/`steps:` wrapper). A case entry must contain either `pattern:` or `default:`, not both — the parser rejects with "pattern and default are mutually exclusive in a case entry" if both are present

**Matching semantics:**
- `${match}` — the entire matched value, accessible inside cases
- `${match.field}` — field access on the matched value
- First-match-wins — cases evaluated top-to-bottom, first matching case executes
- Multiple cases can match the same pattern with different guards (most specific first)
- No-match runtime behavior: if no case matches and no `default:` is present, the match step completes as a no-op — no case executes, no error. Consistent with `if/then` without `else` when condition is false. Authors who want fail-fast add a `default:` case that explicitly errors.
- Parse-time: warn if no `default:` case is present. Runtime expression values cannot be enumerated at parse time — exhaustiveness beyond `default:` presence is not checked
- Parse-time: `default:` must be the last case — a `default:` followed by other cases makes them unreachable. The parser rejects with "default must be the last case." Multiple `default:` cases are also rejected: "only one default case is allowed"

**Value matching (simple dispatch):**
```yaml
- match: ${status}
  cases:
    - pattern: "ACTIVE"
      steps: [activate: ...]
    - pattern: "SUSPENDED"
      steps: [suspend: ...]
    - default: [log: ...]
```

**Structural matching (event routing):**
```yaml
- match: ${event}
  cases:
    - pattern: { type: "io.casehub.work.workitem.completed", priority: "HIGH" }
      steps: [...]
    - pattern: { type: "io.casehub.work.workitem.completed" }
      steps: [...]
```

The first case matches only HIGH-priority completions. The second catches all other completions. Order matters.

**Decorators on match:**
```yaml
- match: ${event.type}
  cases: [...]
  timeout: 30s
  on-error: skip
```

**Match inside forEach:**
```yaml
- match: ${each.event.type}
  cases: [...]
  forEach: { group: events }
```

**Variable scoping for `${match}` and `${match.*}`:**
- **Lifetime:** `${match}` and `${match.*}` are scoped to the matched case's step list. They are not accessible outside the match step or after it completes.
- **Interaction with `${each.*}`:** Coexistence, not shadowing — different prefixes. Inside a match case within a forEach, both `${match}` and `${each.*}` are available.
- **Interaction with `${result.*}`:** Steps inside match cases produce results accessible outside the match step via `${result.step-name.*}`, same as steps inside a block.
- **Nested matches:** Inner `${match}` shadows outer `${match}` within the inner case's scope. The outer `${match}` is restored after the inner match step completes. Same semantics as `${each.*}` in nested forEach.

### 4. `when` → `if` Rename

**Step vocabulary:** All step-layer uses of `when` rename to `if`:
- Step decorator: `when: ${condition}` → `if: ${condition}`
- Import condition: `YamlImport.when` → `YamlImport.if` (field name in Java is `condition` to avoid keyword collision, YAML key is `if`)

**Engine vocabulary:** `Binding.getWhen()` in the engine layer (casehub-engine) is NOT renamed — it IS reactive (fires on context-change events). The vocabulary split is intentional:
- Step-layer `if:` — imperative, evaluated once at execution point
- Engine-layer `when:` — reactive, watches for state changes

**Migration scope:**
- ~20 Java references in yaml-core: `ForEachAdapter.getWhen()` interface + 3 implementations + 5 call sites, `YamlImport.when` record field + 6 call sites, `ImportExpander` + `ModuleExpander`
- ~25 TypeScript references: schema.ts (2 Zod fields), types.ts (1 interface field), expand.ts (~7 references), foreach-expander.ts (~12 references), module-expander.ts (2 references)
- All mechanical renames

### 5. Decorator Evaluation Order (Updated)

| Order | Decorator | Role | Phase |
|-------|-----------|------|-------|
| 1 | `if` | Guard — if false, skip entire step/block | Pre-execution |
| 2 | `forEach` | Iteration — creates per-item context | Structural |
| 3 | `loop` | Repetition — creates per-iteration context | Structural |
| 4 | `on-error` | Error handler | Protection |
| 5 | `timeout` | Deadline | Protection |
| 6 | `trigger`/`wait`/`subscribe` | Pre-action reactive wait | Pre-action |
| 7 | `retry` | Resilience | Protection |
| 8 | `semaphore` | Concurrency control | Protection |
| 9 | `delay` | Pre-action pause | Pre-action |
| 10 | **step type** | action / block / if-else / match / parallel | Execution |
| 11 | `signal`/`publish` | Post-action notification | Post-action |
| 12 | `transition` | Post-action state event | Post-action |
| 13 | `transform` | Post-action data reshape | Post-action |

Position 10 is polymorphic — the decorator stack wraps any step type identically. This is the uniform decorator-on-step-type model (D5).

**Deprecation:** The structural `loop.steps` and `forEach.steps` forms from issue-386 (§1.2, §1.3) are deprecated. Under the uniform model, `loop` and `forEach` are always decorators wrapping position 10 content. Multi-step bodies use `block:` + decorator.

**Deprecation enforcement:** Hard error at parse time — the parser rejects `loop.steps` and `forEach.steps` with a migration message directing authors to `block:` + decorator form. Since issue-386's structural forms have not been implemented or shipped, there are no existing YAML files to migrate — this is a pre-emptive design constraint, not a retroactive break.

```yaml
# Rejected (issue-386 structural form — parse error with migration message):
- loop:
    count: 3
    steps:
      - step-a: ...
      - step-b: ...

# Required form (block + decorator):
- block:
    - step-a: ...
    - step-b: ...
  loop: { count: 3 }
```

**Reserved-but-unassigned keys:** `on-success` and `on-failure` remain in `RESERVED_KEYS` to prevent plugin name collisions but are not active decorators and do not appear in the evaluation order. They are reserved for potential future use (e.g., lifecycle hooks). If a future spec assigns them semantics, they would enter the evaluation order at that point.

**Position 6 variants (`trigger`/`wait`/`subscribe`):** These three keywords are carried forward from issue-386 §2.3 (Signal: `wait: <name>`) and §2.4 (Channel: `subscribe: { channel: <name> }`). They are mutually exclusive at position 6 — a step uses at most one pre-action wait. `wait` and `subscribe` will be added to `RESERVED_KEYS` when issue-386 is implemented; they are not in the current codebase's `RESERVED_KEYS` because the coordination primitives they depend on have not been built yet.

### 6. StepWalker Changes

`Walker` (in yaml-step-runtime) resolves step map entries by classifying keys and constructing typed `ResolvedStep` variants.

**Resolution algorithm:** `resolveOne()` processes a step map in a single pass, classifying each key into one of four categories:

| Category | Keys | Handling |
|----------|------|----------|
| Label | `step` | Ignored (step name only) |
| Step type | `invoke`, `block`, `if` (when `then:` present), `match` (when `cases:` present), `parallel`, plugin name | Determines the `ResolvedStep` variant |
| Structural companion | `then`, `else`, `cases` | Provides data for the identified structural step type |
| Decorator | All other RESERVED_KEYS | Populates the `decorators` map |

**Three-way key classification:** Structural keywords and their companions are classified during the loop, not extracted from the decorators map after collection. The decorators map never contains non-decorator data. This extends the existing pattern where `invoke` and `step` are already handled as separate categories before the RESERVED_KEYS check.

**`if` disambiguation:** `if` is initially collected as a decorator candidate. After the loop, if `then` appears as a structural companion, `if` is reclassified as the step type (IfElseStep) and removed from decorators. If `then` is absent, `if` remains as a decorator (guard).

**Conflict detection:** Each step must have exactly one step type. If the key set contains both a structural keyword and a catalog-resolved action (e.g., `{block: [...], process: {command: deploy.sh}}`), the step is rejected: "ambiguous — structural keyword 'block' and action key 'process' both present."

**Orphaned companion validation:** After step type resolution, any unconsumed structural companions are errors:
- `then` present but no `if` value collected → "Error: `then` requires an `if` condition"
- `else` present but `then` is absent → "Error: `else` requires `then`"
- `cases` present but `match` was not identified as the structural type → "Error: `cases` requires `match`"

This catches misformed steps like `{process: {...}, then: [...]}` (orphaned `then` on a plugin action) or `{process: {...}, else: [...]}` (orphaned `else` with no branching context). The validation runs after step type resolution — if the step resolved to a PluginStep or InvokeStep, any remaining structural companions are definitively orphaned.

**Step type resolution order** (post-loop):

| Priority | Condition | Result |
|----------|-----------|--------|
| 1 | `then` in structural companions | `IfElseStep` — `if` value is condition, `then` is true branch, `else` (if present) is false branch |
| 2 | `block` identified as structural type | `BlockStep` — value must be a list |
| 3 | `match` identified as structural type | `MatchStep` — `cases` must be present in structural companions |
| 4 | `parallel` identified as structural type | `ParallelStep` — value must be a list |
| 5 | Catalog-resolved action | `PluginStep` |
| 6 | `invoke` present | `InvokeStep` |
| — | None of the above | Error: "no step type identified" |

**Recursive resolution:** `resolveOne()` calls `resolve()` recursively for inner step lists:
- `BlockStep.steps` — the block's step list
- `IfElseStep.thenSteps` and `IfElseStep.elseSteps`
- Each `ResolvedMatchCase.steps` in `MatchStep`
- `ParallelStep.steps`

Error messages include the nesting path (e.g., "Step 2 → block → Step 1: unknown key 'foo'"). No explicit recursion depth limit — YAML indentation and the tipping-point guidance (extract at 2+ nesting levels) are the practical constraints. Step catalog scope is unchanged for nested steps.

`ResolvedStep` gains new variants:

```java
public sealed interface ResolvedStep permits
        ResolvedStep.PluginStep,
        ResolvedStep.InvokeStep,
        ResolvedStep.BlockStep,
        ResolvedStep.IfElseStep,
        ResolvedStep.MatchStep,
        ResolvedStep.ParallelStep {

    Map<String, Object> decorators();

    record BlockStep(
            List<ResolvedStep> steps,
            Map<String, Object> decorators) implements ResolvedStep {
        BlockStep {
            steps = List.copyOf(steps);
            decorators = Map.copyOf(decorators);
        }
    }

    record IfElseStep(
            String condition,
            List<ResolvedStep> thenSteps,
            List<ResolvedStep> elseSteps,
            Map<String, Object> decorators) implements ResolvedStep {
        IfElseStep {
            thenSteps = List.copyOf(thenSteps);
            elseSteps = elseSteps != null ? List.copyOf(elseSteps) : List.of();
            decorators = Map.copyOf(decorators);
        }
    }

    record MatchStep(
            String scrutinee,
            List<ResolvedMatchCase> cases,
            Map<String, Object> decorators) implements ResolvedStep {
        MatchStep {
            cases = List.copyOf(cases);
            decorators = Map.copyOf(decorators);
        }
    }

    record ParallelStep(
            List<ResolvedStep> steps,
            Map<String, Object> decorators) implements ResolvedStep {
        ParallelStep {
            steps = List.copyOf(steps);
            decorators = Map.copyOf(decorators);
        }
    }
}
```

**`ResolvedMatchCase`** (in yaml-step-runtime, alongside `ResolvedStep`):

```java
public record ResolvedMatchCase(
        MatchPattern pattern,
        String guard,
        List<ResolvedStep> steps) {
    public ResolvedMatchCase {
        steps = List.copyOf(steps);
    }
}
```

`ResolvedMatchCase` holds resolved steps (consistent with `BlockStep`, `IfElseStep`, and `ParallelStep`), while the parse-layer `MatchCase` in yaml-core retains raw maps. `Walker` converts from `MatchCase` to `ResolvedMatchCase` during resolution, recursively resolving each case's step list.

### 7. Pattern Matching Types (yaml-core)

New types in `io.casehub.yaml.core.step`:

```java
public sealed interface MatchPattern {

    boolean matches(Object scrutinee);

    record ValuePattern(Object value) implements MatchPattern {
        public boolean matches(Object scrutinee) {
            return Objects.equals(value, scrutinee);
        }
    }

    // Top-level subset match: each specified field must be present with an equal value.
    // Extra top-level fields in the scrutinee are ignored.
    // Nested values are compared via Objects.equals (exact equality, not recursive subset).
    record StructuralPattern(Map<String, Object> fields) implements MatchPattern {
        public StructuralPattern {
            fields = Map.copyOf(fields);
        }

        public boolean matches(Object scrutinee) {
            if (!(scrutinee instanceof Map<?, ?> map)) return false;
            for (var entry : fields.entrySet()) {
                if (!Objects.equals(map.get(entry.getKey()), entry.getValue()))
                    return false;
            }
            return true;
        }
    }

    record DefaultPattern() implements MatchPattern {
        public boolean matches(Object scrutinee) {
            return true;
        }
    }
}
```

The `DefaultPattern` variant represents the `default:` catch-all case. It always matches, making the sealed interface exhaustive — switch expressions over `MatchPattern` handle all variants without a catch-all branch. The parser maps the `default:` YAML syntax (step list directly as value, no `pattern:`/`steps:` wrapper) to a `MatchCase(new DefaultPattern(), null, steps)`.

```java
public record MatchCase(
        MatchPattern pattern,
        String guard,
        List<Map<String, Object>> steps) {

    public MatchCase {
        steps = List.copyOf(steps);
    }
}
```

`MatchPattern` lives in yaml-core (zero-dep — uses only `java.util.Objects` and `java.util.Map`). Guard evaluation uses `ExpressionEngine` at the consuming-module layer.

### What changes where

| Module | Change |
|--------|--------|
| `yaml-core/` | `MatchPattern` sealed interface (ValuePattern, StructuralPattern, DefaultPattern) in `io.casehub.yaml.core.step` |
| `yaml-core/` | `MatchCase` record in `io.casehub.yaml.core.step` |
| `yaml-core/` | `YamlImport.when` → `YamlImport.condition` (Java field, YAML key `if`) |
| `yaml-core/` | `ForEachAdapter.getWhen()` → `ForEachAdapter.getCondition()` |
| `yaml-core/` | `ImportExpander`, `ModuleExpander` — update references |
| `yaml-jackson/` | Jackson mixins for MatchPattern, MatchCase |
| `yaml-jackson/` | YamlImport mixin updated for `if` key |
| `yaml-step-runtime/` | `ResolvedStep` gains `BlockStep`, `IfElseStep`, `MatchStep`, `ParallelStep` variants |
| `yaml-step-runtime/` | `ResolvedMatchCase` record — resolved-layer case type with `List<ResolvedStep>` steps |
| `yaml-step-runtime/` | `Walker` updated: three-way key classification, recursive resolution, structural step type detection |
| `yaml-step-runtime/` | `RESERVED_KEYS` updated: `when` → `if`, add `then`, `else`, `match`, `cases`, `block`. `then`/`else`/`cases` are structural companions (reserved, not decorators). `on-success`/`on-failure` remain reserved-but-unassigned |
| `yaml-step-runtime/` | `StepSchemaComposer.DECORATOR_KEYS` updated to match RESERVED_KEYS. Schema `oneOf` array extended with variants for `block:`, `if/then/else`, `match/cases`, `parallel:` structural step types |

### What does NOT change

- `yaml-plugin-api/` — unchanged (plugins are actions, not control flow)
- `yaml-plugin-processor/` — unchanged (APT generates action bindings)
- `yaml-core/` orchestration primitives — unchanged (`Semaphore`, `Latch`, `Channel`, etc.)
- Engine-layer `Binding.getWhen()` — unchanged (reactive, correct vocabulary)
- Decorator evaluation order positions 1-9, 11-13 — unchanged (position 1 renamed from `when` to `if`)

## Future Extensions

These constructs are compatible with the design and slot in without tension:

| Construct | Type | Interaction |
|-----------|------|-------------|
| `try/catch/finally` | Structural step type | Position 10, decorators wrap it |
| `select` (CSP channel) | Structural step type | Position 10, decorators wrap it |
| `for` (range iteration) | Decorator | New position in decorator order |
| Reactive `when` rules | Separate construct | No vocabulary collision (D2 vocabulary split) |

## References

- `yaml-step-runtime/src/main/java/io/casehub/yaml/step/catalog/StepWalker.java` — current step resolution
- `yaml-step-runtime/src/main/java/io/casehub/yaml/step/catalog/ResolvedStep.java` — sealed step types
- `yaml-core/src/main/java/io/casehub/yaml/core/module/YamlImport.java` — import record with `when` field
- `yaml-core/src/main/java/io/casehub/yaml/core/foreach/ForEachAdapter.java` — `getWhen()` interface
- `specs/issue-386-runtime-orchestration/2026-09-22-runtime-orchestration-primitives-design.md` — decorator evaluation order, parallel:, when-pairs
- `specs/issue-386-runtime-orchestration/decisions.md` — D3 (dropped if/else — revisited here)
- `specs/issue-429-yaml-type-system/2026-09-25-dynamic-step-catalog-design.md` — step catalog, StepWalker
- `specs/issue-429-yaml-type-system/2026-09-25-block-level-iteration-design.md` — import-level forEach/loop
- Java 21+ pattern matching (JEP 441) — syntax/structure precedent
- CNCF Serverless Workflow 1.0 — `do:` considered, rejected (D1)
- GitHub #386, #429, #432
