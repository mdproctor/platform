# Unified Step Runtime API — Java/TS parity

**Covers:** #483
**Repo:** casehubio/platform
**Depends on:** #429 (dynamic step catalog — landed), #439 (ParameterType convergence — subsumed by this issue)
**Cross-repo:** casehubio/casehub-pages#506 (TS naming alignment)

## Problem

The step system has three sources of unnecessary ceremony:

1. **"Step" prefix on every type name.** `Declaration`, `StepParameter`, `StepParameterType`, `Action`, `Result`, `StepCatalog`, `StepPluginRegistry`. The module path provides namespace context — the prefix is redundant.

2. **Two ParameterType enums.** Module `ParameterType` (STRING, LIST, INTEGER, NUMBER, BOOLEAN) and step `StepParameterType` (STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT). Converters exist between them on both Java and TS sides — proof that one concept was split.

3. **Mandatory APT codegen for Java plugins.** Every `@StepPlugin` record requires compile-time code generation of a `*Action` class, JSON schema, and registry manifest. The registration path should be: write a record, it works.

The step catalog browser (pages#501) revealed these asymmetries. This issue establishes a unified API contract — `PluginRegistry.register(Definition)` — that all registration mechanisms feed into.

## Scope

**In scope:**
- Unified `Definition` / `Parameter` / `ParameterType` / `Result` / `Action` types (renamed from Step* prefix)
- `PluginRegistry` interface replacing `StepCatalog`
- CDI scanner for dynamic `@Plugin` discovery (default path)
- APT codegen repositioned as optional optimization
- Portability model on `Definition`
- Single `ParameterType` enum (module + step unification)

**Out of scope:**
- TS implementation (pages#506 tracks TS-side alignment)
- IDE JSON Schema generation (#437)
- Security model for invoke handlers (#440)

## Design

### Architectural rule: yaml-plugin-api is J2CL-safe forever

D1 makes yaml-core depend on yaml-plugin-api. yaml-core is J2CL-transpilable. This means yaml-plugin-api inherits a hard J2CL constraint. Any future change to yaml-plugin-api that uses JDK APIs not available in J2CL (e.g., `java.util.concurrent`, reflection, CDI annotations) would transitively break yaml-core.

**Rule:** yaml-plugin-api must remain zero-dependency AND J2CL-safe. This is a binding architectural constraint, documented in CLAUDE.md's yaml-plugin-api section. Enforced by: no new imports outside `java.lang`, `java.util` (excluding `java.util.concurrent`), `java.time`, `java.io`, `java.net.URI`.

### Architectural constraint change: yaml-core gains a dependency

CLAUDE.md currently states: *"yaml-core/ must remain zero-dependency"*. ARC42STORIES.MD §5 describes yaml-core as "zero deps". This spec adds yaml-core → yaml-plugin-api.

**Why this is acceptable:** yaml-plugin-api is itself zero-dependency and J2CL-safe. yaml-core therefore remains transitively zero-dep — no external artifact enters the transitive closure. The constraint was established to prevent yaml-core from pulling in frameworks (Jackson, CDI, etc.) that would break J2CL transpilation and downstream consumers. yaml-plugin-api satisfies both criteria.

**Updated constraint:** yaml-core depends only on yaml-plugin-api, which is zero-dep and J2CL-safe. yaml-core remains transitively zero-dep and J2CL-transpilable.

**Files to update:**
- `CLAUDE.md` line 28: change "yaml-core/ must remain zero-dependency" to "yaml-core/ depends only on yaml-plugin-api (zero-dep, J2CL-safe) — yaml-core remains transitively zero-dep"
- `ARC42STORIES.MD` §5: update yaml-core description from "zero deps" to "depends on yaml-plugin-api only — transitively zero-dep"

### Layer 1: Shared types (yaml-plugin-api)

All types drop the "Step" prefix. Package: `io.casehub.yaml.plugin.api`.

#### ParameterType (unified)

Replaces both `io.casehub.yaml.core.module.ParameterType` and `io.casehub.yaml.core.step.StepParameterType`.

```java
package io.casehub.yaml.plugin.api;

public enum ParameterType {
    STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT;

    public boolean isScalar() {
        return this != ARRAY && this != OBJECT;
    }

    public boolean validate(Object value) {
        return switch (this) {
            case STRING  -> value instanceof String;
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case NUMBER  -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case ARRAY   -> value instanceof java.util.List;
            case OBJECT  -> value instanceof java.util.Map;
        };
    }

    public Object parseScalar(String value) {
        return switch (this) {
            case STRING  -> value;
            case INTEGER -> Integer.parseInt(value);
            case NUMBER  -> Double.parseDouble(value);
            case BOOLEAN -> switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "true", "yes", "on", "y", "1" -> true;
                case "false", "no", "off", "n", "0" -> false;
                default -> throw new IllegalArgumentException(
                        "'" + value + "' is not a boolean value. "
                        + "Expected: true/false/yes/no/on/off/y/n/1/0");
            };
            case ARRAY, OBJECT -> throw new IllegalArgumentException(
                    "Cannot parse '" + this + "' from string");
        };
    }

    public boolean canAccept(ParameterType outputType) {
        if (this == outputType) return true;
        if (this == STRING && outputType.isScalar()) return true;
        if (this == NUMBER && outputType == INTEGER) return true;
        return false;
    }

    public static ParameterType fromString(String name) {
        return switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "STRING" -> STRING;
            case "INTEGER" -> INTEGER;
            case "NUMBER", "DECIMAL" -> NUMBER;
            case "BOOLEAN" -> BOOLEAN;
            case "ARRAY", "LIST" -> ARRAY;
            case "OBJECT" -> OBJECT;
            default -> throw new IllegalArgumentException(
                    "Unknown parameter type '" + name + "'");
        };
    }
}
```

**LIST→ARRAY migration and parsing semantics:** The enum constant `LIST` is removed; `fromString("LIST")` returns `ARRAY` for backward compatibility. The comma-splitting parsing behavior (module ParameterType's `parse()` → `ParsedValue.ListValue`) is NOT on the unified `ParameterType` — it is a module-layer concern. `ParsedValue` and its sealed subtypes remain in yaml-core's module package as a module-internal parsing utility. Module code that previously called `ParameterType.parse(value)` will call a module-local parser that delegates to `ParameterType.parseScalar()` for scalars and does comma-splitting for ARRAY.

**canAccept() with complex types:** `STRING.canAccept(ARRAY)` returns false — string coercion only works for scalar types. `STRING.canAccept(OBJECT)` returns false. ARRAY and OBJECT only accept themselves. This is a change from the previous module ParameterType where STRING accepted all non-LIST types, but module parameters never used ARRAY or OBJECT, so no existing code is affected.

**ValueType stays in yaml-core.** `io.casehub.yaml.core.type.ValueType` (STRING, INTEGER, BOOLEAN, NUMBER) remains for codegen-specific `javaTypes()` and `accepts(String)` methods used by yaml-codegen. Bridge: `ValueType.toParameterType()` returns the corresponding `ParameterType` value. No bridge in the reverse direction needed — ParameterType doesn't reference ValueType.

#### Parameter

Replaces `io.casehub.yaml.core.step.StepParameter`. Moves to yaml-plugin-api.

```java
package io.casehub.yaml.plugin.api;

import java.util.List;

public record Parameter(
        ParameterType type,
        boolean required,
        String defaultValue,
        List<String> allowedValues,
        String format,
        String description) {

    public Parameter {
        if (type == null) type = ParameterType.STRING;
        if (allowedValues == null) allowedValues = List.of();
        if (defaultValue != null && !type.isScalar()) {
            throw new IllegalArgumentException(
                    "Default values are only supported for scalar types, not " + type);
        }
    }
}
```

#### Result (renamed from StepResult)

```java
package io.casehub.yaml.plugin.api;

public sealed interface Result permits Result.Success, Result.Failure {

    boolean isSuccess();
    java.util.Map<String, Object> output();
    default java.util.Map<String, Object> executionMetadata() { return java.util.Map.of(); }

    record Success(java.util.Map<String, Object> output,
                   java.util.Map<String, Object> executionMetadata) implements Result {
        // ... (same as existing StepResult.Success)
        @Override public boolean isSuccess() { return true; }
    }

    record Failure(String message) implements Result {
        @Override public boolean isSuccess() { return false; }
        @Override public java.util.Map<String, Object> output() { return java.util.Map.of(); }
    }

    static Result of(java.util.Map<String, Object> output) { return new Success(output, java.util.Map.of()); }
    static Result of(java.util.Map<String, Object> output, java.util.Map<String, Object> metadata) { return new Success(output, metadata); }
    static Result failed(String message) { return new Failure(message); }
}
```

#### Action (renamed from StepAction)

```java
package io.casehub.yaml.plugin.api;

@FunctionalInterface
public interface Action {
    Result execute(java.util.Map<String, Object> parameters, ServiceRegistry services);
}
```

#### Portability

```java
package io.casehub.yaml.plugin.api;

public enum Portability {
    UNIVERSAL,
    JAVA,
    TS,
    BOTH
}
```

**Portability semantics:** Portability describes *language runtime* dependency, not host capability. UNIVERSAL means "not tied to Java or TS runtime — the step communicates via protocol (HTTP, subprocess, MCP)." Host requirements (specific binaries, Python interpreter, OS commands) are a separate deployment validation concern, not a portability concern. A REST call to `http://internal-service/api` is UNIVERSAL even though it requires network access to the service.

**BOTH semantics:** The author has implemented the step in both Java and TS. Each runtime's registry validates that its local implementation exists. The Java registry checks that the `@Plugin` record or programmatic registration exists. The TS registry checks that the TS implementation is registered. If one side is missing, the step is available but with degraded portability (effectively JAVA or TS). Cross-runtime validation is NOT required — each runtime validates its own side.

#### Plugin (renamed from @StepPlugin)

```java
package io.casehub.yaml.plugin.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Plugin {
    String value();
    String description() default "";
    Portability portability() default Portability.JAVA;
}
```

#### Definition (the unified registration type)

Replaces `CatalogEntry`. Carries both metadata and execution capability. This is the type that `PluginRegistry.register()` accepts and `PluginRegistry.resolve()` returns.

```java
package io.casehub.yaml.plugin.api;

import java.util.Map;

public record Definition(
        String name,
        String description,
        Map<String, Parameter> inputs,
        Map<String, Parameter> outputs,
        Portability portability,
        Action action) {

    public Definition {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Definition requires a name");
        if (inputs == null) inputs = Map.of();
        if (outputs == null) outputs = Map.of();
        if (portability == null) portability = Portability.JAVA;
        if (action == null)
            throw new IllegalArgumentException("Definition requires an action");
    }

    public static Builder of(String name) { return new Builder(name); }

    public static final class Builder {
        private final String name;
        private String description;
        private final java.util.LinkedHashMap<String, Parameter> inputs = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<String, Parameter> outputs = new java.util.LinkedHashMap<>();
        private Portability portability = Portability.JAVA;
        private Action action;

        private Builder(String name) { this.name = name; }

        public Builder description(String description) { this.description = description; return this; }

        public Builder input(String name, ParameterType type, boolean required) {
            inputs.put(name, new Parameter(type, required, null, null, null, null));
            return this;
        }

        public Builder input(String name, Parameter parameter) {
            inputs.put(name, parameter);
            return this;
        }

        public Builder output(String name, ParameterType type) {
            outputs.put(name, new Parameter(type, false, null, null, null, null));
            return this;
        }

        public Builder output(String name, Parameter parameter) {
            outputs.put(name, parameter);
            return this;
        }

        public Builder portability(Portability portability) { this.portability = portability; return this; }

        public Builder execute(Action action) { this.action = action; return this; }

        public Definition build() {
            return new Definition(name, description,
                    java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(inputs)),
                    java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(outputs)),
                    portability, action);
        }
    }
}
```

**Programmatic registration example:**
```java
registry.register(Definition.of("compute-tax")
    .description("Computes tax for a transaction")
    .input("amount", ParameterType.NUMBER, true)
    .input("jurisdiction", ParameterType.STRING, true)
    .output("tax", ParameterType.NUMBER)
    .portability(Portability.JAVA)
    .execute((params, services) -> {
        double amount = ((Number) params.get("amount")).doubleValue();
        String jurisdiction = (String) params.get("jurisdiction");
        double tax = services.lookup(TaxService.class).compute(amount, jurisdiction);
        return Result.of(Map.of("tax", tax));
    })
    .build());
```

#### PluginRegistry

Replaces `StepCatalog`. Single interface for both registration and resolution.

```java
package io.casehub.yaml.plugin.api;

import java.util.Optional;
import java.util.Set;

public interface PluginRegistry {

    void register(Definition definition);

    Optional<Definition> resolve(String actionName);

    Set<String> availableActions();
}
```

### Layer 2: YAML declaration model (yaml-core)

yaml-core gains a dependency on yaml-plugin-api. Its step types are renamed per D2.

#### Declaration (renamed from StepDefinition)

The YAML-parsed declaration. Carries metadata + `InvokeBinding` (how to call the step) but not the executable code. The YAML source resolves the InvokeBinding into an `Action` and constructs a `Definition` for registration.

```java
package io.casehub.yaml.core.step;

import io.casehub.yaml.plugin.api.Parameter;

import java.util.Map;

public record Declaration(
        String name,
        String description,
        Map<String, Parameter> inputs,
        Map<String, Parameter> outputs,
        InvokeBinding invoke,
        Portability portability) {

    public Declaration {
        if (inputs == null) inputs = Map.of();
        if (outputs == null) outputs = Map.of();
    }

    public String qualifiedName(String namespace) {
        return namespace.isEmpty() ? name : namespace + "." + name;
    }
}
```

Note: `Declaration` now uses `Parameter` from yaml-plugin-api directly (not a local StepParameter copy). The `StepParameter` type in yaml-core is deleted — `Parameter` in yaml-plugin-api replaces it. `Portability` is nullable — when null, portability is inferred from the invoke binding (see Path 3). When present, it overrides binding inference.

#### DeclarationFile (renamed from StepDefinitionFile)

```java
package io.casehub.yaml.core.step;

import java.util.Map;

public record DeclarationFile(
        String namespace,
        Map<String, Declaration> actions) {
    // ... (same structure, new names)
}
```

#### ParameterType migration in module package

`io.casehub.yaml.core.module.ParameterType` is deleted. All references update to `io.casehub.yaml.plugin.api.ParameterType`. The module-specific `parse()` → `ParsedValue` logic moves to a new `ModuleParameterParser` utility class in `io.casehub.yaml.core.module`:

```java
package io.casehub.yaml.core.module;

import io.casehub.yaml.plugin.api.ParameterType;

public final class ModuleParameterParser {

    public static ParsedValue parse(ParameterType type, String value) {
        return switch (type) {
            case STRING  -> new ParsedValue.StringValue(value);
            case ARRAY   -> new ParsedValue.ListValue(
                    java.util.Arrays.stream(value.split(",")).map(String::trim).toList());
            case INTEGER -> new ParsedValue.IntegerValue(Integer.parseInt(value));
            case NUMBER  -> new ParsedValue.NumberValue(Double.parseDouble(value));
            case BOOLEAN -> new ParsedValue.BooleanValue(
                    io.casehub.yaml.core.condition.Truthiness.isTruthy(value));
            case OBJECT  -> throw new IllegalArgumentException(
                    "Module parameters do not support OBJECT type");
        };
    }
}
```

`ParsedValue` sealed interface and its subtypes remain in `io.casehub.yaml.core.module` — unchanged.

### Layer 3: Registration paths (yaml-step-runtime)

All three registration paths produce a `Definition` and call `PluginRegistry.register()`. The registry is path-agnostic.

#### Path 1: CDI scanner (default)

New class in yaml-step-runtime. Discovers `@Plugin` beans at CDI startup, reflects on record components, builds `Definition`, registers.

```java
package io.casehub.yaml.step.catalog;

import io.casehub.yaml.plugin.api.*;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.*;

public class PluginScanner {

    void onStartup(@Observes @jakarta.annotation.Priority(100) StartupEvent event,
                   PluginRegistry registry,
                   jakarta.enterprise.inject.Instance<Object> beans) {
        // Discover all @Plugin-annotated beans
        // For each:
        //   1. Read @Plugin annotation → name, description, portability
        //   2. Reflect on record components → build inputs (Parameter per component)
        //   3. Find @Execute method → capture as lambda
        //   4. Construct Definition
        //   5. Call registry.register(definition)
    }
}
```

**Record component → Parameter mapping:**

| Record component type | ParameterType | Required? |
|----------------------|---------------|-----------|
| `String` | STRING | @Required annotation present |
| `int` / `Integer` | INTEGER | primitives always required |
| `long` / `Long` | INTEGER | primitives always required |
| `double` / `Double` | NUMBER | primitives always required |
| `boolean` / `Boolean` | BOOLEAN | primitives always required |
| `java.util.List<?>` | ARRAY | @Required annotation present |
| `java.util.Map<?,?>` | OBJECT | @Required annotation present |

Kebab-case YAML keys derived from camelCase field names (same logic as existing `BinderEmitter.toKebabCase()`).

**Execute method wrapping:** At scan time, the scanner captures the record constructor and @Execute method. At execution time, the lambda:
1. Extracts parameters from the input map (type-converting via kebab-case keys)
2. Invokes the record constructor with the extracted values
3. Invokes the @Execute method on the constructed record (passing service params)
4. Returns the Result

This is functionally identical to what the APT-generated `*Action` class does, but via reflection instead of generated code.

#### Path 2: APT codegen (optional optimization)

The existing `yaml-plugin-processor` stays and continues to generate:
- `*Action.java` — Action implementation class
- `*.schema.json` — JSON Schema for IDE validation
- `*.json` — registry manifest

The generated `*Action` class now implements `Action` (not `Action`). The `AptPluginSource` in yaml-step-runtime loads these manifests and constructs `Definition` instances for registration.

**Processor migration (yaml-plugin-processor):**

`StepPluginProcessor`, `BinderEmitter`, and `RegistryEmitter` require updating:

- `StepPluginProcessor`: `StepPlugin.class` → `Plugin.class` in `getElementsAnnotatedWith()`, `StepResult.class` → `Result.class` in return type check, reads `annotation.portability()` from `@Plugin` and passes to `PluginModel`
- `BinderEmitter`: generated imports change `Action` → `Action`, `Result` → `Result`; generated class `implements Action` (not `Action`); generated `execute()` returns `Result` (not `Result`); `name()` override is DROPPED (Action is a pure `@FunctionalInterface`)
- `RegistryEmitter`: emits `"portability"` field in manifest JSON from `PluginModel.portability()`

**Updated manifest JSON format:**
```json
{
  "name": "compute-tax",
  "description": "Computes tax for a transaction",
  "pluginClass": "io.example.ComputeTax",
  "actionClass": "io.example.ComputeTaxAction",
  "schemaResource": "META-INF/yaml-plugins/compute-tax.schema.json",
  "portability": "JAVA"
}
```

**Portability pipeline (APT → Definition):** `AptPluginSource.loadManifest()` reads the `"portability"` field from the manifest JSON. If present, parses to `Portability.valueOf()`; if absent (backward compatibility with pre-migration manifests), defaults to `Portability.JAVA`. The portability value is passed to `Definition` construction. This ensures that `@Plugin(value = "my-step", portability = Portability.UNIVERSAL)` flows correctly through the APT pipeline in standalone/J2CL mode where CDI scanning is unavailable.

**When both paths are active:** If a project has both the CDI scanner and APT codegen, the same plugin could be discovered twice. The CDI scanner runs at priority 100. `AptPluginSource` runs at priority 200. First registration wins — the CDI scanner registers first, and the APT source's `putIfAbsent` is a no-op.

**When APT is absent:** The CDI scanner handles everything. No generated code needed. This is the zero-ceremony path.

**When CDI is absent (standalone, J2CL):** APT codegen + manifest loading is the only Java path. Programmatic `register()` is the universal fallback.

#### Path 3: YAML step definitions

The existing `YamlDefinitionSource` parses YAML step definition files, resolves `InvokeBinding` via `InvokeHandler` to produce an `Action`, and constructs a `Definition` for registration.

**Portability inference from invoke binding:**

| InvokeBinding variant | Default Portability | Rationale |
|----------------------|--------------------|----|
| `Rest` | UNIVERSAL | HTTP — any runtime can make the call |
| `Graphql` | UNIVERSAL | HTTP — GraphQL over HTTP |
| `Mcp` | UNIVERSAL | Protocol-based tool call |
| `Process` | UNIVERSAL | Any runtime can spawn a subprocess |
| `Script` | UNIVERSAL | Any runtime can spawn a subprocess |
| `Agent` | UNIVERSAL | Protocol-level agent invocation |

All YAML-defined invoke bindings are UNIVERSAL because they communicate via protocol, not via language-specific code. Host requirements (specific binaries, Python interpreter, network access) are deployment validation concerns, not portability concerns. Portability answers "which language runtimes can execute this step?" — the answer for protocol-based steps is "all of them."

YAML step definitions can override portability explicitly:
```yaml
actions:
  compute-risk:
    description: Risk computation
    portability: java
    invoke:
      rest:
        url: "..."
```

### Layer 4: Runtime (yaml-step-runtime CDI wiring)

#### CompositePluginRegistry (replaces CompositeStepCatalog)

```java
package io.casehub.yaml.step.catalog;

import io.casehub.yaml.plugin.api.*;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CompositePluginRegistry implements PluginRegistry {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(CompositePluginRegistry.class.getName());

    private final Map<String, Definition> entries = new ConcurrentHashMap<>();

    @Override
    public void register(Definition definition) {
        Definition existing = entries.putIfAbsent(definition.name(), definition);
        if (existing != null) {
            LOG.warning("Duplicate registration for '" + definition.name()
                    + "' — keeping first registration");
        }
    }

    @Override
    public Optional<Definition> resolve(String actionName) {
        return Optional.ofNullable(entries.get(actionName));
    }

    @Override
    public Set<String> availableActions() {
        return Set.copyOf(entries.keySet());
    }
}
```

The `CatalogSource` SPI is replaced by the three registration paths calling `register()` directly. No more `populate(Map)` + priority-based composition. Registration order is controlled by CDI `@Priority` on the scanner/source beans.

#### InvokeHandler (unchanged API, renamed types)

```java
package io.casehub.yaml.step;

import io.casehub.yaml.core.step.Declaration;
import io.casehub.yaml.core.step.InvokeBinding;
import io.casehub.yaml.plugin.api.Action;

public interface InvokeHandler {
    boolean supports(InvokeBinding binding);
    Action create(Declaration declaration, InvokeBinding binding);
}
```

The six invoke handlers (McpInvokeHandler, RestInvokeHandler, GraphqlInvokeHandler, ScriptInvokeHandler, AgentInvokeHandler, ProcessInvokeHandler) update their return type from `Action` to `Action` and their parameter type from `Declaration` to `Declaration`.

### Pre-flight validation

Before executing a YAML playbook, the executor resolves all steps via `PluginRegistry.resolve()`, checks portability vs the current runtime. Any incompatible step → refuse with clear error. No partial execution.

#### RuntimeType

```java
package io.casehub.yaml.plugin.api;

public enum RuntimeType {
    JAVA, TS;

    public boolean canExecute(Portability portability) {
        return switch (portability) {
            case UNIVERSAL, BOTH -> true;
            case JAVA -> this == JAVA;
            case TS -> this == TS;
        };
    }
}
```

**Compatibility matrix:**

| Portability | Java Runtime | TS Runtime |
|-------------|-------------|------------|
| UNIVERSAL | ✅ | ✅ |
| JAVA | ✅ | ❌ |
| TS | ❌ | ✅ |
| BOTH | ✅ | ✅ |

The current runtime type is determined at startup — in Quarkus the runtime is always `RuntimeType.JAVA`. The TS runtime (pages#506) will use `RuntimeType.TS`.

```java
for (String actionName : playbook.referencedActions()) {
    Definition def = registry.resolve(actionName)
        .orElseThrow(() -> new UnknownActionException(actionName));

    if (!currentRuntime.canExecute(def.portability())) {
        throw new PortabilityException(actionName, def.portability(), currentRuntime);
    }
}
```

### Migration summary

#### Types renamed (IntelliJ rename refactor)

| Old (current) | New | Module |
|---|---|---|
| `Action` | `Action` | yaml-plugin-api |
| `Result` | `Result` | yaml-plugin-api |
| `Plugin` | `Plugin` | yaml-plugin-api |
| `StepParameter` | `Parameter` | yaml-plugin-api (moved from yaml-core) |
| `StepParameterType` | *(deleted — absorbed into ParameterType)* | yaml-plugin-api |
| `Declaration` (yaml-core) | `Declaration` | yaml-core |
| `DeclarationFile` | `DeclarationFile` | yaml-core |
| `DeclarationParser` | `DeclarationParser` | yaml-core |
| `Validator` | `Validator` | yaml-core |
| `StepCatalog` | *(deleted — replaced by PluginRegistry)* | yaml-plugin-api |
| `CatalogEntry` | *(deleted — replaced by Definition)* | yaml-plugin-api |
| `CatalogSource` | *(deleted — replaced by direct register() calls)* | — |
| `CompositeStepCatalog` | `CompositePluginRegistry` | yaml-step-runtime |
| `StepExecutionEvent` | `ActionExecutionEvent` | yaml-step-runtime |
| `ValidatingAction` | `ValidatingAction` | yaml-step-runtime |

#### Types moved

| Type | From | To |
|---|---|---|
| `Parameter` (was StepParameter) | yaml-core/step | yaml-plugin-api |
| `ParameterType` (unified) | yaml-core/module + yaml-core/step | yaml-plugin-api |

#### Types deleted

| Type | Reason |
|---|---|
| `io.casehub.yaml.core.module.ParameterType` | Unified into yaml-plugin-api ParameterType |
| `io.casehub.yaml.core.step.StepParameterType` | Unified into yaml-plugin-api ParameterType |
| `io.casehub.yaml.step.StepCatalog` | Replaced by PluginRegistry |
| `io.casehub.yaml.step.CatalogEntry` | Replaced by Definition |
| `io.casehub.yaml.step.CatalogSource` | Replaced by direct register() calls |

#### Types updated (internal — use new types)

| Type | Module | Changes |
|---|---|---|
| `Walker` | yaml-step-runtime | `StepCatalog` → `PluginRegistry`, `CatalogEntry` → `Definition` |
| `StepSchemaComposer` | yaml-step-runtime | `StepCatalog` → `PluginRegistry`, `StepParameter` → `Parameter`, `StepParameterType` → `ParameterType` |
| `McpStepCatalogWiring` | yaml-step-runtime | `CatalogSource` → direct `PluginRegistry.register()`, `CatalogEntry` → `Definition`, `Declaration` → `Declaration`, `StepParameter` → `Parameter`, `StepParameterType` → `ParameterType` |
| `YamlDefinitionSource` | yaml-step-runtime | `CatalogSource` → direct `PluginRegistry.register()`, `CatalogEntry` → `Definition`, `Action` → `Action`, `ValidatingAction` → `ValidatingAction` |
| `ImportScopedStepCatalog` | yaml-step-runtime | `StepCatalog` → `PluginRegistry`, `CatalogEntry` → `Definition` |
| `DecoratedExecution` | yaml-step-runtime | `Result` → `Result` |
| `AptPluginSource` | yaml-step-runtime | `CatalogSource` → direct `PluginRegistry.register()`, `CatalogEntry` → `Definition`, `Declaration` → `Declaration`, `StepParameter` → `Parameter`, `StepParameterType` → `ParameterType`, `Action` → `Action`. Reads portability from manifest JSON |
| `McpToolSource` | yaml-step-runtime | `CatalogSource` → direct `PluginRegistry.register()`, `CatalogEntry` → `Definition`, `Declaration` → `Declaration`, `Result` → `Result` |
| `StepPluginProcessor` | yaml-plugin-processor | `Plugin` → `Plugin`, `Result` → `Result`. Reads `portability()` from `@Plugin` annotation |
| `BinderEmitter` | yaml-plugin-processor | Generated code: `Action` → `Action`, `Result` → `Result`, `implements StepAction` → `implements Action`, drops `name()` override |
| `RegistryEmitter` | yaml-plugin-processor | Emits `portability` field in manifest JSON |

#### New types

| Type | Module | Purpose |
|---|---|---|
| `Definition` | yaml-plugin-api | Unified registration type (metadata + action) |
| `Definition.Builder` | yaml-plugin-api | Fluent builder for programmatic registration |
| `PluginRegistry` | yaml-plugin-api | register() + resolve() + availableActions() |
| `Portability` | yaml-plugin-api | UNIVERSAL / JAVA / TS / BOTH |
| `PluginScanner` | yaml-step-runtime | CDI scanner for @Plugin discovery |
| `CompositePluginRegistry` | yaml-step-runtime | ConcurrentHashMap-backed registry |
| `ModuleParameterParser` | yaml-core/module | Comma-split parsing (extracted from deleted ParameterType.parse()) |
| `RuntimeType` | yaml-plugin-api | JAVA / TS — runtime compatibility check |

#### Dependency changes

| Module | Change |
|---|---|
| yaml-plugin-api | Gains: ParameterType, Parameter, Definition, PluginRegistry, Portability. Stays zero-dep, J2CL-safe. |
| yaml-core | Gains dependency on yaml-plugin-api. Uses ParameterType, Parameter from there. Loses: StepParameter, StepParameterType, module ParameterType. |
| yaml-step-runtime | Internal refactor. CompositeStepCatalog → CompositePluginRegistry. CatalogSource deleted. PluginScanner added. |

#### Validator API update

`Validator` → `Validator` (yaml-core). Updated to accept `Map<String, Parameter>` directly instead of a specific record type, so it works with both `Declaration` and `Definition`:

```java
package io.casehub.yaml.core.step;

import io.casehub.yaml.plugin.api.Parameter;
import java.util.Map;

public final class Validator {

    private Validator() {}

    public static java.util.List<String> validateInputs(
            String actionName,
            Map<String, Object> params,
            Map<String, Parameter> declarations) {
        return validateParams(actionName, params, declarations, "input");
    }

    public static java.util.List<String> validateOutputs(
            String actionName,
            Map<String, Object> outputs,
            Map<String, Parameter> declarations) {
        return validateParams(actionName, outputs, declarations, "output");
    }

    // validateParams and validateFormat unchanged — operates on Parameter directly
}
```

The `ValidatingAction` (renamed from `ValidatingAction`) calls `Validator.validateInputs(name, params, definition.inputs())` — using `Definition.inputs()` which returns `Map<String, Parameter>`.

#### ActionExecutionEvent (renamed from StepExecutionEvent)

The existing `StepExecutionEvent` has 11 fields; only `bindingType` and `resultClassification` are populated by `ValidatingStepAction.fireEvent()` (the remaining 5 — `actorId`, `tenancyId`, `inputHash`, `parentStepName`, `executionEnvironment` — are always null). All 11 fields are preserved in the renamed record:

```java
package io.casehub.yaml.step;

public record ActionExecutionEvent(
        String actionName, long durationMs, boolean success,
        java.util.Map<String, Object> metadata, String bindingType,
        String resultClassification, String actorId,
        String tenancyId, String inputHash,
        String parentStepName, String executionEnvironment) {

    public ActionExecutionEvent(String actionName, long durationMs,
                                boolean success, java.util.Map<String, Object> metadata) {
        this(actionName, durationMs, success, metadata,
             null, null, null, null, null, null, null);
    }
}
```

#### Cross-repo consumers

| Consumer | Repo | References | Migration |
|---|---|---|---|
| `CbrPlanToStepConverter` | casehub-engine | `StepCatalog` | Update to `PluginRegistry` |
| `CbrPlanToStepConverterTest` | casehub-engine | `StepCatalog` (stub) | Update stub to implement `PluginRegistry` |

Cross-repo updates tracked as follow-on issue on casehubio/casehub-engine.

#### Documentation updates

| File | Change |
|---|---|
| `CLAUDE.md` | Update yaml-core zero-dep constraint, update yaml-plugin-api description |
| `ARC42STORIES.MD` §5 | Update yaml-core description |

## References

- yaml-plugin-api/src/main/java/io/casehub/yaml/plugin/api/StepAction.java — existing Action contract
- yaml-plugin-api/src/main/java/io/casehub/yaml/plugin/api/StepResult.java — existing Result contract
- yaml-plugin-api/src/main/java/io/casehub/yaml/plugin/api/StepPlugin.java — existing @Plugin annotation
- yaml-core/src/main/java/io/casehub/yaml/core/step/StepDefinition.java — type to rename to Declaration
- yaml-core/src/main/java/io/casehub/yaml/core/step/StepParameter.java — type to move to yaml-plugin-api
- yaml-core/src/main/java/io/casehub/yaml/core/step/StepParameterType.java — type to absorb into ParameterType
- yaml-core/src/main/java/io/casehub/yaml/core/module/ParameterType.java — type to absorb into unified ParameterType
- yaml-core/src/main/java/io/casehub/yaml/core/type/ValueType.java — scalar foundation, stays in yaml-core
- yaml-step-runtime/src/main/java/io/casehub/yaml/step/CatalogEntry.java — replaced by Definition
- yaml-step-runtime/src/main/java/io/casehub/yaml/step/StepCatalog.java — replaced by PluginRegistry
- yaml-step-runtime/src/main/java/io/casehub/yaml/step/catalog/AptPluginSource.java — stays, adapted
- yaml-step-runtime/src/main/java/io/casehub/yaml/step/catalog/CompositeStepCatalog.java — becomes CompositePluginRegistry
- yaml-plugin-processor/src/main/java/io/casehub/yaml/plugin/processor/StepPluginProcessor.java — stays, optional
- docs/specs/issue-429-yaml-type-system/2026-09-25-dynamic-step-catalog-design.md — prior spec (#429)
- Issue #483 — unified step runtime API
- Issue #439 — ParameterType convergence (subsumed)
- casehubio/casehub-pages#506 — TS naming alignment
