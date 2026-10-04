# Step Plugin YAML Vocabulary — plugin-name-as-key dispatch + P1 plugins

**Covers:** #447
**Repo:** casehubio/platform
**Depends on:** #433 (dynamic step catalog — landed), #446 (ProcessInvokeHandler → ProcessExecutor — landed), ProcessExecutor SPI (landed: `1a832316`)

## Problem

The step catalog (#433) assembles runtime step registries from YAML definitions, APT-generated plugins, and MCP tools. But the YAML surface for invoking steps is generic — `action: name` + `data:` requires the YAML author to know the action name string and provides no schema validation or IDE autocomplete at the key level.

For operations teams writing playbooks, the step action name should BE the YAML keyword — schema-validated, IDE-completable, first-class vocabulary.

Additionally, the `@StepPlugin` APT generates `Action` implementations that call `services.lookup(Type.class)`, but no CDI-backed `ServiceRegistry` exists. And the foundational P1 plugins (process, rest-call, assert) haven't been written.

## Scope

**In scope:**
- Step walker utility for plugin-name-as-key dispatch
- `CdiServiceRegistry` — CDI bridge for `ServiceRegistry` SPI
- P1 `@StepPlugin` records: `process`, `rest-call`, `assert`
- `StepSchemaComposer` — composed JSON Schema from all registered plugins

**Out of scope:**
- AptPluginSource classpath scanning (#443 — separate issue)
- McpToolSource CDI wiring (#444 — separate issue)
- IDE JSON Schema generation Maven plugin (#437)
- Security model for invoke handlers (#440)

## Design

### 1. Step Walker (yaml-step-runtime)

New utility class `Walker` in `io.casehub.yaml.step.catalog`.

#### Key classification

Each step in a playbook is a YAML map. The walker classifies each key:

| Classification | Keys | Behaviour |
|---------------|------|-----------|
| Plugin action | Any key matching `StepCatalog.resolve()` | Value is the params map |
| Invoke escape hatch | `invoke` | Value is InvokeBinding spec (existing path) |
| Step label | `step` | Value is the step name string |
| Decorator | `when`, `on-success`, `on-failure`, `forEach`, `loop`, `retry`, `timeout`, `delay`, `on-error`, `trigger`, `transform`, `signal`, `publish`, `transition`, `parallel`, `semaphore`, `barrier`, `quorum`, `race` | Value is decorator config |
| Unknown | Anything else | Error with available actions listed |

Reserved keys (decorators + `step` + `invoke`) are checked first — they cannot be shadowed by plugin names. If a plugin registers a name that collides with a reserved key, `CompositeStepCatalog` rejects it at initialization time with a descriptive error.

#### API

```java
public final class StepWalker {

    private static final Set<String> RESERVED_KEYS = Set.of(
            "step", "invoke",
            "when", "on-success", "on-failure", "forEach", "loop",
            "retry", "timeout", "delay", "on-error", "trigger",
            "transform", "signal", "publish", "transition",
            "parallel", "semaphore", "barrier", "quorum", "race");

    public static List<ResolvedStep> resolve(
            List<Map<String, Object>> steps, StepCatalog catalog) { ... }
}
```

#### ResolvedStep sealed interface

```java
public sealed interface ResolvedStep {

    Map<String, Object> decorators();

    record PluginStep(
            CatalogEntry entry,
            Map<String, Object> params,
            Map<String, Object> decorators) implements ResolvedStep {}

    record InvokeStep(
            Map<String, Object> invokeSpec,
            Map<String, Object> decorators) implements ResolvedStep {}
}
```

`PluginStep` carries the full `CatalogEntry` — both `Declaration` (for validation) and `Action` (for execution). `InvokeStep` carries the raw invoke spec for resolution via the existing `InvokeHandler` chain.

#### Resolution algorithm

For each step map:
1. Partition keys into `reservedKeys` and `candidateKeys` (everything not in `RESERVED_KEYS`)
2. Extract `step` value (optional label) and `invoke` value (optional escape hatch)
3. If `invoke` is present and candidateKeys is empty → `InvokeStep`
4. If candidateKeys has exactly one key that resolves in the catalog → `PluginStep`
5. If candidateKeys has multiple catalog matches → error: ambiguous step
6. If candidateKeys has zero catalog matches and no `invoke` → error: unknown action, list available
7. Collect reserved keys (minus `step` and `invoke`) into `decorators` map

#### YAML surface — what changes

**Before** (never implemented — #151 spec only):
```yaml
steps:
  - step: run-deploy
    action: process-execute
    data:
      command: deploy.sh
```

**After** (plugin-name-as-key):
```yaml
steps:
  - process:
      command: deploy.sh
      args: ["--env", "production"]
    timeout: 30s

  - rest-call:
      url: /api/notifications
      method: POST
    when: "${deploy.success}"

  - assert:
      expression: "${result.process.exitCode} == 0"
```

**Escape hatch** (`invoke:` for ad-hoc endpoints without writing a plugin):
```yaml
steps:
  - invoke:
      mcp:
        tool: custom-risk-tool
    step: risk-check
```

The `action:` + `data:` surface from the #151 spec is not implemented. #447 supersedes it with plugin-name-as-key.

### 2. CdiServiceRegistry (yaml-step-runtime)

`@ApplicationScoped` CDI bridge for the `ServiceRegistry` SPI. Uses Quarkus Arc for dynamic bean lookup.

```java
@ApplicationScoped
public class CdiServiceRegistry implements ServiceRegistry {

    @Override
    public <T> T lookup(Class<T> serviceType) {
        InstanceHandle<T> handle = Arc.container().instance(serviceType);
        if (!handle.isAvailable()) {
            throw new IllegalArgumentException(
                    "No CDI bean registered for " + serviceType.getName()
                    + ". Ensure the implementation is on the classpath.");
        }
        return handle.get();
    }
}
```

This leverages the existing CDI bean landscape — `ProcessExecutor` resolves to `DefaultProcessExecutor`, `ExpressionEngineRegistry` resolves to `DefaultExpressionEngineRegistry`, etc. The `@DefaultBean` / `@Alternative @Priority` displacement chain applies transparently.

`MapServiceRegistry` (already in yaml-plugin-api) remains available for testing and standalone use.

### 3. P1 `@StepPlugin` Records (yaml-step-runtime)

Three plugin records in `io.casehub.yaml.step.plugin`. The APT (yaml-plugin-processor) generates for each:
- `<Plugin>Action` binder class implementing `Action`
- `META-INF/yaml-plugins/<name>.schema.json` (JSON Schema)
- `META-INF/yaml-plugins/<name>.json` (registry manifest)

#### ProcessPlugin

```java
@StepPlugin(value = "process", description = "Executes a system process via ProcessExecutor")
public record ProcessPlugin(
        @Required String command,
        @Optional List<String> args,
        @Optional String workingDir,
        @Optional String timeout,
        @Optional Boolean mergeStderr) {

    public ProcessPlugin {
        if (args == null) args = List.of();
        if (mergeStderr == null) mergeStderr = false;
    }

    @Execute
    public StepResult run(ProcessExecutor executor) {
        ProcessCommand cmd = ProcessCommand.of(command);
        if (!args.isEmpty()) {
            List<String> full = new java.util.ArrayList<>();
            full.add(command);
            full.addAll(args);
            cmd = ProcessCommand.of(full.toArray(String[]::new));
        }
        if (workingDir != null) {
            cmd = cmd.workingDir(workingDir);
        }
        if (timeout != null) {
            cmd = cmd.timeout(parseTimeout(timeout));
        }
        cmd = cmd.mergeStderr(mergeStderr);

        long start = System.nanoTime();
        ProcessResult result = executor.execute(cmd);
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("exitCode", result.exitCode());
        output.put("stdout", result.stdout() != null ? result.stdout() : "");
        output.put("stderr", result.stderr() != null ? result.stderr() : "");
        output.put("timedOut", result.isTimedOut());

        Map<String, Object> metadata = Map.of("durationMs", durationMs);

        if (!result.isSuccess()) {
            String error = result.stderr() != null && !result.stderr().isBlank()
                    ? result.stderr().trim()
                    : "Process exited with code " + result.exitCode();
            return StepResult.failed(error);
        }

        return StepResult.of(output, metadata);
    }

    private static java.time.Duration parseTimeout(String timeout) { ... }
}
```

**YAML:**
```yaml
- process:
    command: /opt/risk-engine/assess
    args: ["--portfolio", "${portfolio}"]
    working-dir: /workspace
    timeout: 30s
```

Output: `exitCode`, `stdout`, `stderr`, `timedOut`. No output-format parsing (json/csv/lines) — that complexity lives in `ProcessInvokeHandler` for the invoke escape hatch. Plugin consumers use subsequent expression steps if they need structured output.

**Relationship to ProcessInvokeHandler:** Both call `ProcessExecutor`. The plugin is the first-class YAML vocabulary (schema-validated, typed fields, IDE-completable). The InvokeHandler handles the `invoke: { process: ... }` escape hatch. The duplication is intentional — they serve different resolution paths with different ergonomics.

#### RestCallPlugin

```java
@StepPlugin(value = "rest-call", description = "Makes an HTTP request")
public record RestCallPlugin(
        @Required String url,
        @Optional String method,
        @Optional Map<String, Object> body,
        @Optional Map<String, String> headers,
        @Optional String timeout) {

    public RestCallPlugin {
        if (method == null) method = "GET";
    }

    @Execute
    public StepResult run() {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        // Build request, send, return status + body + headers
    }
}
```

**YAML:**
```yaml
- rest-call:
    url: https://api.example.com/notify
    method: POST
    headers:
      Content-Type: application/json
    body:
      message: "Deployment complete"
    timeout: 10s
```

Output: `statusCode`, `body`, `headers`. No credential injection for P1 — follow-on concern.

**Relationship to RestInvokeHandler:** Same pattern as process — plugin is the first-class path, InvokeHandler is the escape hatch.

#### AssertPlugin

```java
@StepPlugin(value = "assert", description = "Asserts an expression evaluates to true")
public record AssertPlugin(
        @Required String expression,
        @Optional String message) {

    @Execute
    public StepResult run(ExpressionEngineRegistry engines) {
        String engineType = engines.resolveDefault(ExpressionContext.CONDITION);
        if (engineType == null) {
            return StepResult.failed("No default expression engine configured for CONDITION context");
        }
        CompiledExpression<Map, Boolean> compiled =
                engines.compile(engineType, expression, Map.class, Boolean.class);
        Boolean result = compiled.eval(Map.of());

        if (Boolean.TRUE.equals(result)) {
            return StepResult.of(Map.of("passed", true));
        }
        String failMessage = message != null ? message : "Assertion failed: " + expression;
        return StepResult.failed(failMessage);
    }
}
```

**YAML:**
```yaml
- assert:
    expression: "${result.process.exitCode} == 0"
    message: "Process failed — expected exit code 0"
```

**Variable resolution flow:** `${result.process.exitCode}` is resolved by the engine's `VariableResolver` BEFORE the plugin receives it (per #151 spec). The plugin receives `expression: "0 == 0"` and evaluates via the default CONDITION engine (MVEL).

### 4. StepSchemaComposer (yaml-step-runtime)

Utility that composes all registered plugin schemas into a unified step JSON Schema.

```java
public final class StepSchemaComposer {

    public static ObjectNode compose(StepCatalog catalog, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode oneOf = root.putArray("oneOf");

        for (String action : catalog.availableActions()) {
            catalog.resolve(action).ifPresent(entry -> {
                ObjectNode variant = mapper.createObjectNode();
                ObjectNode props = variant.putObject("properties");
                // Load schema from META-INF/yaml-plugins/<action>.schema.json
                // or construct from StepDefinition
                props.set(action, loadOrConstructSchema(entry, mapper));
                variant.putArray("required").add(action);
                oneOf.add(variant);
            });
        }

        // Add invoke escape hatch variant
        ObjectNode invokeVariant = mapper.createObjectNode();
        invokeVariant.putObject("properties").putObject("invoke");
        invokeVariant.putArray("required").add("invoke");
        oneOf.add(invokeVariant);

        // Add shared decorator properties
        ObjectNode sharedProps = root.putObject("properties");
        sharedProps.putObject("step").put("type", "string");
        sharedProps.putObject("when").put("type", "string");
        sharedProps.putObject("timeout").put("type", "string");
        // ... remaining decorators

        return root;
    }
}
```

Output is a JSON Schema node. Writing it to a file is #437's concern (IDE JSON Schema generation Maven plugin).

### 5. POM Changes (yaml-step-runtime)

Add yaml-plugin-processor as annotation processor:

```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.casehub</groupId>
                <artifactId>casehub-platform-yaml-plugin-processor</artifactId>
                <version>${project.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

Add `schema-generator` as a transitive dependency of the processor (needed for APT schema generation). No new compile dependencies on yaml-step-runtime — the processor runs at build time only.

### What changes where

| Module | Change |
|--------|--------|
| `yaml-step-runtime/` | `Walker` utility + `ResolvedStep` sealed interface |
| `yaml-step-runtime/` | `CdiServiceRegistry @ApplicationScoped` |
| `yaml-step-runtime/` | `ProcessPlugin`, `RestCallPlugin`, `AssertPlugin` in `io.casehub.yaml.step.plugin` |
| `yaml-step-runtime/` | `StepSchemaComposer` utility |
| `yaml-step-runtime/` | POM: add yaml-plugin-processor as annotation processor |

### What does NOT change

- `yaml-plugin-api/` — zero-dependency, unchanged
- `yaml-plugin-processor/` — APT unchanged, generates from the new plugins
- `yaml-core/` — step definition model unchanged
- `ProcessInvokeHandler` — remains for the invoke escape hatch path
- `RestInvokeHandler` — remains for the invoke escape hatch path
- `CompositeStepCatalog` — unchanged (reserved-name collision check is additive)

### Testing strategy

- **StepWalker:** Unit tests with mock `StepCatalog`. Test plugin-name-as-key resolution, invoke escape hatch, decorator extraction, error cases (unknown key, ambiguous keys, reserved name collision).
- **CdiServiceRegistry:** Unit test with `Arc.container()` (no `@QuarkusTest` needed — Arc can be initialized programmatically for focused tests). Verify lookup succeeds for registered beans and fails with descriptive error for missing beans.
- **ProcessPlugin:** Unit test with mock `ProcessExecutor` via `MapServiceRegistry`. Verify command construction, timeout parsing, output mapping, failure handling.
- **RestCallPlugin:** Unit test with HTTP stub (WireMock or `HttpServer`). Verify request construction, response mapping.
- **AssertPlugin:** Unit test with mock `ExpressionEngineRegistry` via `MapServiceRegistry`. Verify truthy/falsy evaluation, custom message, missing engine error.
- **StepSchemaComposer:** Unit test verifying schema structure — oneOf contains all registered actions, decorator properties present, invoke variant included.

## Non-goals

- **AptPluginSource classpath scanning** — #443. The P1 plugins produce APT artifacts; discovering them at runtime is #443's concern.
- **McpToolSource CDI wiring** — #444.
- **IDE JSON Schema file generation** — #437.
- **Security model for invoke handlers** — #440.
- **Credential injection for rest-call** — follow-on concern.
- **Output-format parsing (json/csv/lines) for process plugin** — lives in ProcessInvokeHandler for the invoke escape hatch; plugin returns raw output.

## References

- `yaml-step-runtime/src/main/java/io/casehub/yaml/step/StepCatalog.java` — catalog SPI
- `yaml-step-runtime/src/main/java/io/casehub/yaml/step/CatalogEntry.java` — resolved catalog entry
- `yaml-step-runtime/src/main/java/io/casehub/yaml/step/handler/ProcessInvokeHandler.java` — existing process invoke handler
- `yaml-plugin-api/src/main/java/io/casehub/yaml/plugin/api/ServiceRegistry.java` — SPI interface
- `yaml-plugin-api/src/main/java/io/casehub/yaml/plugin/api/MapServiceRegistry.java` — test/standalone impl
- `yaml-plugin-processor/src/main/java/io/casehub/yaml/plugin/processor/BinderEmitter.java` — APT code generation
- `platform-api/src/main/java/io/casehub/platform/api/process/ProcessExecutor.java` — process SPI
- `platform-api/src/main/java/io/casehub/platform/api/process/ProcessCommand.java` — command builder
- `platform-api/src/main/java/io/casehub/platform/api/expression/ExpressionEngineRegistry.java` — expression SPI
- `specs/issue-429-yaml-type-system/2026-09-25-dynamic-step-catalog-design.md` — step catalog design
- `docs/specs/issue-151-orchestration-scope-bridge/2026-09-24-yaml-plugin-api-design.md` — plugin API design
- GitHub #447, #433, #446, #443, #444, #437, #440
