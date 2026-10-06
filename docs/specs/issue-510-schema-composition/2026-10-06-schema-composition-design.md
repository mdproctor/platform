# Schema Composition/Extension Mechanism

**Issue:** casehubio/platform#510
**Branch:** issue-510-schema-composition
**Date:** 2026-10-06

## Problem

Playbook schemas (`client`, `server`, domain schemas like `clinical-server`) define which capabilities are available in a playbook. The front matter parsing, schema registry, and capability model exist. What's missing is the mechanism that connects schemas to the runtime step vocabulary — the composition layer that makes `schema: clinical-server` actually restrict which step keys are valid.

Three gaps:
1. Domain schemas don't inherit base capabilities
2. Plugins don't declare which capability they belong to
3. No mechanism filters the plugin registry by schema

## Design

### Layer 1: Capability Inheritance (yaml-core)

Add a default method to `PlaybookSchemaRegistry`:

```java
default Set<String> effectiveCapabilities(String schemaName) {
    var effective = new java.util.HashSet<String>();
    var visited = new java.util.HashSet<String>();
    var current = schemaName;
    while (current != null) {
        if (!visited.add(current)) {
            throw new IllegalStateException(
                    "Circular schema inheritance: " + visited);
        }
        var desc = resolveOrThrow(current);
        effective.addAll(desc.capabilities());
        current = desc.baseSchema();
    }
    return Set.copyOf(effective);
}
```

Iterative walk up the base chain. Terminates when `baseSchema()` is null (built-ins). Cycle detection via visited set — throws `IllegalStateException` on circular inheritance. No recursion, J2CL-safe.

The stored `PlaybookSchemaDescriptor` retains only its own capabilities. `effectiveCapabilities()` resolves the full chain at query time, preserving the distinction between own and inherited.

**Files changed:**
- `PlaybookSchemaRegistry.java` — add `effectiveCapabilities(String)` default method

### Layer 2: Plugin Capability Binding (yaml-plugin-api + yaml-plugin-processor)

#### @Plugin annotation

Add `capability` field:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Plugin {
    String value();
    String description() default "";
    Portability portability() default Portability.JAVA;
    String capability() default "steps";  // NEW
}
```

Default `"steps"` — the base capability in every schema. Domain-specific plugins declare their capability:

```java
@Plugin(value = "clinical-enroll", capability = "clinical-trial")
public record ClinicalEnrollPlugin(...) { ... }
```

#### Definition record

Add `String capability` as 7th parameter:

```java
public record Definition(
        String name,
        String description,
        Map<String, Parameter> inputs,
        Map<String, Parameter> outputs,
        Portability portability,
        Action action,
        String capability  // NEW — defaults to "steps" when null
) {
    public Definition {
        // ... existing validation
        if (capability == null || capability.isBlank()) capability = "steps";
    }
}
```

Builder gets `.capability(String)`:

```java
public Builder capability(String capability) {
    this.capability = capability;
    return this;
}
```

#### Processor changes

`StepPluginProcessor` reads `capability()` from `@Plugin` → passes to `PluginModel` → emitters include it.

`RegistryEmitter` includes `capability` in the manifest JSON:

```json
{
  "name": "rest-call",
  "description": "Makes an HTTP request",
  "capability": "steps",
  "pluginClass": "...",
  "actionClass": "...",
  "portability": "JAVA",
  "schemaResource": "META-INF/yaml-plugins/rest-call.schema.json"
}
```

**Files changed:**
- `Plugin.java` — add `capability()` default `"steps"`
- `Definition.java` — add `capability` field, update compact constructor, update Builder
- `PluginModel.java` — add `capability` field
- `StepPluginProcessor.java` — read `capability()` from annotation
- `RegistryEmitter.java` — include `capability` in manifest JSON
- `BinderEmitter.java` — no change (generates Action, not Definition)
- `SchemaEmitter.java` — no change (JSON Schema is per-plugin, not per-capability)

#### Call site updates (yaml-step-runtime)

All sources that construct `Definition` pass `capability`:

| Source | Capability value |
|--------|-----------------|
| `AptPluginSource` | Read from manifest JSON `capability` field (default `"steps"`) |
| `PluginScanner` | Read from `@Plugin.capability()` |
| `YamlStepDefinitionSource` | From YAML declaration `capability` field (default `"steps"`) |
| `McpToolSource` | `"mcp-invoke"` (MCP tools are MCP_INVOKE capability) |
| `ScriptSource` | `"steps"` (scripts are basic steps) |

### Layer 3: Schema-Filtered Registry (yaml-core)

New class in `io.casehub.yaml.core.playbook`:

```java
public final class SchemaFilteredRegistry implements PluginRegistry {

    private final PluginRegistry delegate;
    private final Set<String> allowedCapabilities;

    public SchemaFilteredRegistry(PluginRegistry delegate,
                                   Set<String> allowedCapabilities) {
        this.delegate = Objects.requireNonNull(delegate);
        this.allowedCapabilities = Set.copyOf(allowedCapabilities);
    }

    public static SchemaFilteredRegistry forSchema(
            PluginRegistry delegate,
            PlaybookSchemaRegistry schemas,
            String schemaName) {
        return new SchemaFilteredRegistry(
                delegate, schemas.effectiveCapabilities(schemaName));
    }

    @Override
    public void register(Definition definition) {
        delegate.register(definition);
    }

    @Override
    public Optional<Definition> resolve(String actionName) {
        return delegate.resolve(actionName)
                .filter(d -> allowedCapabilities.contains(d.capability()));
    }

    @Override
    public Set<String> availableActions() {
        // Return only actions whose capability is allowed
        return delegate.availableActions().stream()
                .filter(name -> delegate.resolve(name)
                        .map(d -> allowedCapabilities.contains(d.capability()))
                        .orElse(false))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
```

The `availableActions()` override is important — when StepWalker rejects an unknown key, the error message lists available actions. With the filter, the error only shows steps valid for the current schema, which gives actionable feedback.

The `forSchema()` factory bridges `PlaybookSchemaRegistry` and `PluginRegistry`. The Walker doesn't need to know about schemas — it receives a filtered registry and everything Just Works.

**Files changed:**
- New: `SchemaFilteredRegistry.java` in `yaml-core/.../playbook/`

### Integration Point (yaml-step-runtime, not in this issue)

The runtime wiring — where `PlaybookParser.parse()` produces a `PlaybookDocument`, the schema is resolved, and a `SchemaFilteredRegistry` is passed to `StepWalker` — lives in `yaml-step-runtime`. This is the CDI-level integration that connects the mechanism. It's a natural follow-on once the mechanism exists.

For this issue, the mechanism is testable without CDI:

```java
var schemas = new MapPlaybookSchemaRegistry();
schemas.register(PlaybookSchemaDescriptor.domain(
    "clinical-server", "server", Set.of("clinical-trial")));

var registry = new CompositePluginRegistry();
registry.register(Definition.of("rest-call").capability("steps")...build());
registry.register(Definition.of("correlate").capability("correlation")...build());
registry.register(Definition.of("clinical-enroll").capability("clinical-trial")...build());
registry.register(Definition.of("spotlight").capability("spotlight")...build());

var filtered = SchemaFilteredRegistry.forSchema(registry, schemas, "clinical-server");
assertThat(filtered.resolve("rest-call")).isPresent();       // steps — in server
assertThat(filtered.resolve("correlate")).isPresent();        // correlation — in server
assertThat(filtered.resolve("clinical-enroll")).isPresent();  // clinical-trial — in domain
assertThat(filtered.resolve("spotlight")).isEmpty();           // spotlight — client-only
```

## Test Strategy

### yaml-core tests

1. **PlaybookSchemaRegistry.effectiveCapabilities():**
   - Built-in schema returns own capabilities
   - Domain schema returns own + base capabilities
   - Multi-level chain (domain → domain → built-in) resolves correctly
   - Unknown schema throws
   - Cycle detection triggers at depth > 5

2. **SchemaFilteredRegistry:**
   - `resolve()` returns matching capability plugins
   - `resolve()` filters out non-matching capability plugins
   - `availableActions()` only lists matching plugins
   - `register()` delegates to underlying registry
   - `forSchema()` factory bridges schema registry and plugin registry
   - Error messages from StepWalker show only schema-appropriate actions

### yaml-plugin-api tests

3. **Definition record:**
   - Null capability defaults to "steps"
   - Blank capability defaults to "steps"
   - Explicit capability preserved
   - Builder `.capability()` works

### yaml-plugin-processor tests

4. **StepPluginProcessor:**
   - Plugin with explicit capability includes it in manifest
   - Plugin without capability defaults to "steps" in manifest

## Scope Boundaries

**In scope:**
- Capability inheritance (default method)
- `capability` field on `@Plugin` and `Definition`
- `SchemaFilteredRegistry`
- Processor manifest update
- Call site updates for Definition constructor
- Tests for all of the above

**Out of scope (follow-on):**
- JSON Schema composition for offline/IDE validation
- CDI wiring in yaml-step-runtime to auto-apply schema filtering
- YAML declaration file `capability` field parsing
- Migration tooling for existing `.yaml` → `.playbook.yaml`

## References

- `PlaybookSchemaRegistry.java` — existing registry interface
- `MapPlaybookSchemaRegistry.java` — built-in schema registration
- `PlaybookSchemaDescriptor.java` — schema descriptor record
- `PlaybookCapabilities.java` — capability string constants
- `Definition.java` — plugin definition record (yaml-plugin-api)
- `Plugin.java` — @Plugin annotation (yaml-plugin-api)
- `StepPluginProcessor.java` — APT processor (yaml-plugin-processor)
- `StepWalker.java:118-132` — unknown key rejection via PluginRegistry
- `CompositePluginRegistry.java` — ConcurrentHashMap registry
- Issue #510 — parent issue
- HANDOFF.md — prior session context
