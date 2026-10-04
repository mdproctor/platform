# CaseHub YAML Language Guide

yaml-core is a composable meta-language that layers on top of any YAML structure. It provides variables, conditions, iteration, data, modules, typed expansion, and inline control flow — constructs that a host application can adopt individually or together. The host owns the YAML schema; yaml-core owns the dynamic behaviour.

Think of it as what Jinja is to HTML, or what HCL is to Terraform — except it works with any YAML structure, not a specific one.

---

## How It Layers

yaml-core does not parse YAML. It does not define what your YAML means. It operates on the parsed result — a `Map<String, Object>` tree — and transforms it.

Your application:
1. Defines its own YAML schema (what keys exist, what they mean)
2. Parses YAML into maps (Jackson, SnakeYAML, whatever you use)
3. Hands the maps to yaml-core constructs for variable substitution, iteration, conditional filtering, and module composition
4. Gets back transformed maps with all dynamic behaviour resolved

A simple application might use only variables. A full declarative platform uses everything.

```
Your YAML schema (pages, desiredstate, engine, anything)
    │
    ├── Variable resolution     ${prefix.name}
    ├── Conditional inclusion   if: "${var.enabled}"
    ├── ForEach expansion       forEach + stamp N copies
    ├── Typed CSV data          inline tabular data as iteration sources
    ├── Module system           import, parameterise, alias, compose
    ├── Inline control flow     block, if/then/else, match/cases
    └── Typed expansion         ModuleBridge<T> for domain-specific compilation
```

---

## 1. Variables

**Syntax:** `${prefix.name}`

Every variable has a prefix that identifies its source and a name that identifies the value within that source. The prefix is the key idea — it makes clear where each value comes from.

```yaml
# DesiredState: infrastructure configuration
variables:
  payment_provider: stripe
  currency: USD

nodes:
  payment:
    spec:
      provider: ${var.payment_provider}
      currency: ${var.currency}
```

```yaml
# Pages: dashboard templating
properties:
  metricsUrl: /api/metrics

datasets:
  - uuid: metrics
    url: ${metricsUrl}

html:
  html: >-
    <div style="background-color: ${bgColor}">
      <h2>${value}</h2>
      <p>${title}</p>
    </div>
```

### Default values

Use `:-` for fallback when a variable might not be set:

```yaml
timeout: ${var.request_timeout:-30}
region: ${var.deploy_region:-us-east-1}
```

### Deferred resolution

Some variables can't be resolved at compile time — they exist at runtime. Register a prefix as "deferred" and yaml-core passes it through literally:

```yaml
# ${result.response.status} stays as-is in the output — resolved at runtime
route: ${result.response.status}
```

### Variable sources

Applications register sources per prefix. Built-in sources:

| Source | What it resolves |
|--------|-----------------|
| `VariableSource.env()` | `System.getenv()` |
| `VariableSource.systemProperty()` | `System.getProperty()` |
| `VariableSource.nested(data)` | Dot-path drilling into nested maps |
| `VariableSource.chain(a, b, c)` | First non-null across multiple sources |
| `VariableSource.forEachContext(...)` | ForEach iteration values (see §3) |
| `VariableSource.matchContext(scrutinee)` | Match case variable scoping (see §9) |

Scoping is immutable — `resolver.withScope("myprefix", mySource)` returns a new resolver with the added prefix. This makes it safe to layer sources without mutation.

---

## 2. Conditional Inclusion (`if`)

**Syntax:** `if: "${var.expression}"`

A boolean guard on any element. The expression resolves to a string, then truthiness evaluation decides include or exclude.

```yaml
nodes:
  gift-wrapping:
    if: "${var.gift_wrapping_enabled}"
    spec:
      style: premium

  premium-support:
    if: "${var.tier:-free}"
    spec:
      channel: phone
```

### Truthiness

Case-insensitive evaluation. These pairs are equivalent:

| True | False |
|------|-------|
| `true` | `false` |
| `yes` | `no` |
| `on` | `off` |
| `y` | `n` |
| `1` | `0` |

Anything else delegates to the configured expression engine (MVEL, JQ, or custom) for evaluation. If no expression engine is configured, throws `ConditionEvaluationException`.

Expression engines support operators like `==`, `!=`, `>`, `<`, `>=`, `<=`, `contains`, `startsWith`, `endsWith`. The expression receives the resolved variable values and returns a boolean.

### Interaction with forEach

When `if` appears on an element that also has `forEach`, the condition is evaluated per iteration value. Individual copies can be excluded while others are included:

```yaml
nodes:
  regional-cache:
    forEach:
      as: region
      in: [us-east, eu-west, ap-south]
    if: "${var.cache_enabled_${each.region}}"
    spec:
      region: ${each.region}
```

---

## 3. ForEach Expansion

**What it does:** Takes a single element and stamps N copies of it, one per value in a list. Each copy gets a stamped ID and access to the current iteration value via `${each.*}`.

### Inline iteration

The values are declared directly:

```yaml
nodes:
  shipping:
    forEach:
      as: warehouse
      in: [us-east, eu-west, ap-south]
    spec:
      warehouse: ${each.warehouse}
      endpoint: https://${each.warehouse}.shipping.internal
```

This produces three nodes: `shipping.us-east`, `shipping.eu-west`, `shipping.ap-south`. The original `shipping` ID is gone — replaced by the stamped copies.

### Named iteration groups

When multiple elements iterate over the same list, declare the group once and reference it by name:

```yaml
iterations:
  warehouses:
    as: warehouse
    in: [us-east, eu-west, ap-south]

nodes:
  shipping:
    forEach: warehouses
    spec:
      warehouse: ${each.warehouse}

  monitoring:
    forEach: warehouses
    spec:
      target: shipping.${each.warehouse}
```

Both `shipping` and `monitoring` expand in lockstep. Cross-references between them are rewritten automatically — `dependsOn: [monitoring]` becomes `dependsOn: [monitoring.us-east]` in the `shipping.us-east` copy.

### ID stamping

The stamped ID follows a predictable pattern: `originalId.iterationValue`.

| Original ID | Iteration value | Stamped ID |
|-------------|----------------|------------|
| `shipping` | `us-east` | `shipping.us-east` |
| `cache` | `hot` | `cache.hot` |
| `db-replica` | `reader-1` | `db-replica.reader-1` |

### Reference rewriting

After expansion, yaml-core rewrites cross-references between elements in the same iteration group. If `shipping` depends on `monitor`, then `shipping.us-east` depends on `monitor.us-east` — not on the pre-expansion `monitor`.

This is the key difference from a simple loop. It preserves the topology of your graph across expansion.

---

## 4. Typed CSV Data

Inline tabular data with typed columns, usable as forEach sources.

```yaml
data:
  environments: |
    name:STRING,port:INTEGER,enabled:BOOLEAN
    staging,8080,true
    production,9090,yes
    dr,8080,no

nodes:
  service:
    forEach:
      as: env
      in: environments
    if: "${each.env.enabled}"
    spec:
      name: ${each.env.name}
      port: ${each.env.port}
```

### Column types

| Type | Java type | Example values |
|------|-----------|---------------|
| `STRING` | `String` | `hello`, `us-east` |
| `INTEGER` | `Integer` | `8080`, `42` |
| `NUMBER` | `Double` | `3.14`, `0.95` |
| `BOOLEAN` | `Boolean` | `true`, `yes`, `1`, `on` |

Parse-time validation catches type errors before expansion — an `INTEGER` column with a value of `abc` fails immediately, not at runtime.

### Field access

When iterating over CSV data, `${each.<as>.<column>}` drills into row fields:

```yaml
forEach:
  as: env
  in: environments        # references the data source above

spec:
  name: ${each.env.name}       # STRING → staging
  port: ${each.env.port}       # INTEGER → 8080
  active: ${each.env.enabled}  # BOOLEAN → true
```

---

## 5. Modules

The composition system. A module is a reusable, parameterised unit of YAML content that can be imported into other YAML files.

### Defining a module

A module declares its name, typed parameters, and content sections:

```yaml
# modules/order-notifications.yaml
module:
  name: order-notifications
  parameters:
    watched_step:
      type: string
      required: true
      pattern: "^[a-z][a-z0-9-]*$"
    severity:
      type: string
      default: medium
      allowedValues: [low, medium, high, critical]

nodes:
  email-alert:
    type: notification
    dependsOn: [${param.watched_step}]
    spec:
      severity: ${param.severity}
      channel: email
  
  slack-alert:
    type: notification
    dependsOn: [${param.watched_step}]
    spec:
      severity: ${param.severity}
      channel: slack
```

### Importing a module

```yaml
imports:
  - module: order-notifications
    as: payment-alerts
    parameters:
      watched_step: payment
      severity: critical

  - module: order-notifications
    as: shipping-alerts
    parameters:
      watched_step: shipping
```

### Alias prefixing

The `as:` field prefixes all section keys from the imported module. This prevents collisions when importing the same module twice:

| Original key | Alias | Result key |
|-------------|-------|------------|
| `email-alert` | `payment-alerts` | `payment-alerts.email-alert` |
| `slack-alert` | `payment-alerts` | `payment-alerts.slack-alert` |
| `email-alert` | `shipping-alerts` | `shipping-alerts.email-alert` |

Cross-references within the module are rewritten to use the prefixed names — `dependsOn: [email-alert]` inside the module becomes `dependsOn: [payment-alerts.email-alert]` after import.

### Conditional imports

```yaml
imports:
  - module: order-notifications
    as: payment-alerts
    if: "${var.payment_notifications_enabled}"
    parameters:
      watched_step: payment
```

The entire module is included or excluded based on the `if` condition.

### Parameter validation

Parameters are type-checked and constraint-validated at expansion time:

| Constraint | Applies to | What it checks |
|-----------|------------|---------------|
| `required: true` | all types | Parameter must be provided |
| `default: value` | all types | Used when parameter is omitted |
| `minLength` / `maxLength` | STRING, LIST | Length bounds |
| `pattern: "regex"` | STRING, LIST | Each value must match |
| `minimum` / `maximum` | INTEGER, NUMBER | Numeric bounds |
| `allowedValues: [...]` | all types | Enum restriction |
| `constraintDescription` | all types | Human-readable error message |

Validation collects all errors before failing — you see every invalid parameter at once, not one at a time.

### Module outputs

Modules can declare typed output values, computed from parameters and content. Downstream imports can reference them:

```yaml
# Module declares outputs
module:
  name: database
  parameters:
    port:
      type: integer
      default: 5432
  outputs:
    connection_string: "postgres://localhost:${param.port}/mydb"

# Consumer references output
nodes:
  app:
    spec:
      db: ${module.infra-db.connection_string}
```

---

## 6. The Plugin Model (DesiredState)

DesiredState uses yaml-core to implement a plugin-based ops platform. Plugins define **node types** — what a resource looks like, how to check its actual state, and how to provision it.

```yaml
plugin:
  type: test-resource
  version: 1
  resyncInterval: 30s
  auth:
    test-api:
      credentialRef: test-api-credentials

spec:
  fields:
    name: { type: string, required: true }
    count: { type: integer, default: 1, min: 0, max: 100 }
    mode: { type: enum, values: [fast, slow, balanced] }

actual-state:
  steps:
    - rest-call:
        url: "https://${auth.test-api.endpoint}/resources/${spec.name}"
        result: response
    - compare-state:
        present-when: "${result.response.status} == 200"
        drifted-when: "${result.response.body.count} < ${spec.count}"

provisioner:
  provision:
    steps:
      - rest-call:
          method: PUT
          url: "https://${auth.test-api.endpoint}/resources/${spec.name}"
          body: { name: "${spec.name}", count: ${spec.count} }
```

The plugin uses multiple variable prefixes, each resolved by a different source:

| Prefix | Source | Resolved when |
|--------|--------|--------------|
| `${spec.*}` | Declared spec fields from the node definition | Compile time |
| `${auth.*}` | Credential store (API keys, endpoints) | Runtime |
| `${result.*}` | Step outputs (REST responses, command results) | Runtime (deferred) |
| `${var.*}` | User-defined variables section | Compile time |
| `${each.*}` | ForEach iteration context | Expansion time |
| `${match.*}` | Match case scrutinee fields | Runtime (see §9) |
| `${fault.*}` | Fault context (error details) | Runtime (deferred) |

---

## 7. Bridging: Teaching yaml-core About Your Domain

yaml-core knows nothing about pages, desiredstate, or your domain. Host applications teach it through two adapter interfaces.

### ForEachAdapter

Teaches yaml-core how to stamp copies of your domain elements:

```java
public interface ForEachAdapter<E> {
    ForEachDirective getForEach(E element);      // read the forEach field
    String getCondition(E element);              // read the if condition
    E stamp(E element, String stampedId, ...);   // create a copy with new ID + resolved vars
    List<Reference> getReferences(E element);    // extract cross-references for rewriting
    E withReferences(E element, List<Reference>);// rewrite references after expansion
}
```

DesiredState implements `YamlNodeForEachAdapter` — it knows that nodes have a `spec` map to resolve, a `dependsOn` list to rewrite, and a `forEach` field to read.

### ModuleBridge

Teaches yaml-core how to convert between raw maps and your typed domain model:

```java
public interface ModuleBridge<T> {
    T fromSections(Map<String, Map<String, Object>> sections);  // raw → typed
    Map<String, Map<String, Object>> toSections(T content);     // typed → raw
    // optional:
    SectionContentRewriter rewriter();     // rewrite refs after alias prefixing
    Map<String, String> deriveOutputs(T);  // compute module outputs from content
}
```

DesiredState implements `DesiredStateModuleBridge` — it knows that sections contain `nodes`, `rules`, and `invariants`, and that `dependsOn` references need rewriting when module aliases prefix IDs.

Pages does not implement either adapter — it uses only variable resolution, which needs no bridging.

---

## 8. Composition: Putting It All Together

A complete DesiredState YAML using all constructs:

```yaml
variables:
  environment: production
  cache_enabled: "true"
  monitoring_enabled: "true"

data:
  regions: |
    name:STRING,primary:BOOLEAN
    us-east,true
    eu-west,false
    ap-south,false

imports:
  - module: monitoring-stack
    as: infra
    if: "${var.monitoring_enabled}"
    parameters:
      alert_threshold: "90"

iterations:
  regional:
    as: region
    in: regions

nodes:
  api-gateway:
    forEach: regional
    spec:
      region: ${each.region.name}
      primary: ${each.region.primary}
      cache: ${var.cache_enabled}

  cache:
    forEach: regional
    if: "${var.cache_enabled}"
    spec:
      region: ${each.region.name}
      ttl: 300

  database:
    spec:
      engine: postgres
      version: "16"
```

**What yaml-core does with this:**

1. **Variables** resolve `${var.environment}`, `${var.cache_enabled}`, `${var.monitoring_enabled}`
2. **CSV data** parses the `regions` table into typed rows
3. **Module import** expands `monitoring-stack` with alias `infra`, conditional on `monitoring_enabled`
4. **ForEach** stamps `api-gateway` and `cache` per region row → `api-gateway.us-east`, `api-gateway.eu-west`, etc.
5. **Conditions** filter `cache` copies per region — only included where `cache_enabled` is truthy
6. **Reference rewriting** updates any `dependsOn` between `api-gateway` and `cache` to use stamped IDs

The output is a flat map of fully resolved, stamped nodes with no dynamic constructs remaining — ready for your reconciliation engine, renderer, or deployment pipeline.

---

## 9. Inline Control Flow (Step Types)

Steps in a `steps:` list support structural control flow. Every step has a **type** (what it does) and optional **decorators** (how it does it).

### Step types

| Step type | Key | Purpose |
|-----------|-----|---------|
| Plugin action | plugin name | Execute one action |
| Invoke | `invoke:` | Execute one binding (MCP, script, agent, process) |
| Block | `block:` | Execute N steps sequentially |
| If/else | `if:` + `then:` | Branch on condition |
| Match | `match:` + `cases:` | Branch on pattern |
| Parallel | `parallel:` | Execute N steps concurrently |

### `block:` — compound step

Groups steps into a single unit that decorators can wrap:

```yaml
- block:
    - rest-call: { url: ${endpoint}/status }
    - assert: { condition: ${result.rest-call.status} == "ok" }
  loop: { count: 3, until: ${status} == "ok" }
  timeout: 30s
```

### `if/then/else` — branching

Two-way branching over step lists. Disambiguated from the `if` decorator guard by presence of `then:`:

```yaml
- if: ${risk} == 'HIGH'
  then:
    - escalate: ...
    - notify-team: ...
  else:
    - proceed: ...
```

`if` without `then:` is a guard (decorator) — skips the step if false. `if` with `then:` is a structural branch.

### `match/cases` — pattern matching

N-way branching with value matching, structural matching, and guards:

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

**Matching semantics:** first-match-wins, scalar equality or top-level structural subset match. `${match}` and `${match.*}` expose the matched value inside cases. `default:` must be the last case.

### Decorator evaluation order

All decorators apply uniformly to all step types:

| Order | Decorator | Role |
|-------|-----------|------|
| 1 | `if` | Guard — skip if false |
| 2 | `forEach` | Iteration |
| 3 | `loop` | Repetition |
| 4 | `on-error` | Error handler |
| 5 | `timeout` | Deadline |
| 6 | `trigger`/`wait`/`subscribe` | Pre-action wait |
| 7 | `retry` | Resilience |
| 8 | `semaphore` | Concurrency control |
| 9 | `delay` | Pre-action pause |
| 10 | **step type** | action / block / if-else / match / parallel |
| 11 | `signal`/`publish` | Post-action notification |
| 12 | `transition` | Post-action state event |
| 13 | `transform` | Post-action data reshape |

---

## 10. Plugins and the Registry

Steps are not hard-coded — they come from a **plugin registry** that discovers actions at startup from multiple sources. Each source contributes definitions with typed parameters and an execution binding.

### Writing a plugin

A plugin is a Java record annotated with `@Plugin`. The CDI `PluginScanner` discovers `@Plugin` records at startup and registers them into the `PluginRegistry`. Optionally, the annotation processor generates JSON Schema, an `Action` implementation, and a registry manifest at compile time:

```java
@Plugin(value = "rest-call", description = "Makes an HTTP request")
public record RestCallPlugin(
        @Required String url,
        @Optional String method,
        @Optional Map<String, Object> body,
        @Optional Map<String, String> headers,
        @Optional String timeout) {

    @Execute
    public Result run() {
        // implementation
        return Result.of(Map.of("status-code", 200, "body", "..."));
    }
}
```

**Discovery paths (all converge on `PluginRegistry.register(Definition)`):**
- **CDI scanner (default):** `PluginScanner` discovers `@Plugin` records as CDI beans at startup — no annotation processor needed
- **APT codegen (optional):** annotation processor generates `Action` implementation + JSON Schema + manifest for ahead-of-time compilation

**What the annotation processor generates (when used):**
- `META-INF/yaml-plugins/rest-call.schema.json` — JSON Schema from the record fields
- `RestCallPluginAction implements Action` — wraps the record instantiation + `@Execute` method
- `META-INF/yaml-plugins/rest-call.json` — manifest linking name → action class → schema → portability

**Field annotations:**
- `@Required` — parameter must be provided (schema: `required`)
- `@Optional` — parameter is optional (nullable in the record)
- `@Execute` — marks the method that runs the step (must return `Result`)

**Return types:** `Result` is a sealed interface — `Result.of(Map)` for success, `Result.failed(message)` for failure.

### Registration sources

All sources register `Definition` objects into the `CompositePluginRegistry`. First registration wins on name collision:

| Source | What it discovers | Registration |
|--------|-------------------|--------------|
| `PluginScanner` | `@Plugin` records discovered as CDI beans | CDI startup scan |
| `AptPluginSource` | `@Plugin` records via `META-INF/yaml-plugins/` manifests on the classpath | Startup populate |
| `McpToolSource` | MCP tool definitions — each tool becomes an action with a tool invoker binding | Event-driven |
| `ScriptSource` | Script files (`.py`, `.js`, `.mjs`) with companion `.schema.yaml` files on the filesystem | Startup populate |
| `YamlDefinitionSource` | YAML step definition files — declarative step definitions with typed parameters and invoke bindings | Startup populate |

### Script auto-discovery

`ScriptSource` scans configured directories for scripts with companion schema files:

```
scripts/
  validate.py           # script file — runtime inferred from extension
  validate.schema.yaml  # parameter schema
  transform.js
  transform.schema.yaml
```

Schema file format:

```yaml
description: Validate input data
inputs:
  data:
    type: object
    required: true
    description: The data to validate
  strict:
    type: boolean
    description: Enable strict mode
```

**Runtime detection:** `.py` → `python3`, `.js`/`.mjs` → `node`. The script receives parameters as JSON on stdin and writes results as JSON to stdout.

### YAML step definition files

Steps can also be defined declaratively in YAML. A step definition file declares a namespace, actions with typed parameters, and an invoke binding:

```yaml
namespace: compliance

actions:
  check-sanctions:
    description: Screen entity against sanctions lists
    inputs:
      entity-id:
        type: string
        required: true
      lists:
        type: string
        enum: [ofac, eu, un, all]
        default: all
    outputs:
      match:
        type: boolean
      details:
        type: object
    invoke:
      rest:
        method: POST
        url: https://compliance.internal/screen

  validate-kyc:
    description: Validate KYC documents
    inputs:
      document-type:
        type: string
        required: true
        enum: [passport, drivers-license, national-id]
    invoke:
      agent:
        descriptor: kyc-validator
        model: claude-sonnet-5
        structured-output: true
```

Actions are namespaced — `compliance.check-sanctions` in YAML steps. The invoke binding determines how the action executes (see below).

### Invoke bindings

Every step definition has an `InvokeBinding` that determines how it executes:

| Binding | Key | How it runs |
|---------|-----|-------------|
| `Mcp` | `tool` | Calls an MCP tool by name |
| `Script` | `runtime`, `script` | Spawns a process (`python3 script.py`) with JSON stdin/stdout |
| `Process` | `command`, `args` | Spawns a command with arguments |
| `Agent` | `descriptor`, `model` | Invokes an AI agent |
| `Rest` | `method`, `url` | Makes an HTTP request |
| `Graphql` | `query` | Executes a GraphQL query |

### Schema type safety

The step catalog enforces type safety at two levels:

1. **Parse-time:** `Walker` resolves step maps against the registry. Unknown action keys are rejected with the list of available actions. Parameter types are checked against the `Parameter` definitions.

2. **Schema composition:** `StepSchemaComposer` generates a composed JSON Schema covering all discovered actions. Each action becomes a `oneOf` variant with its parameters schema. Structural step types (block, if/else, match, parallel) are included as additional variants. This schema can be served to editors for validation and auto-complete.

```java
// Generate composed schema for all discovered steps
ObjectNode schema = StepSchemaComposer.compose(catalog, objectMapper);
```

The composed schema includes:
- One `oneOf` variant per plugin action (with typed parameter properties)
- `invoke:` escape hatch variant
- `block:`, `if/then/else`, `match/cases`, `parallel:` structural variants
- Shared decorator properties (`if`, `forEach`, `loop`, `timeout`, `retry`, etc.)

### Adding the step runtime dependency

```xml
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-platform-yaml-step-runtime</artifactId>
</dependency>
```

For plugin authoring only (no runtime needed):

```xml
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-platform-yaml-plugin-api</artifactId>
</dependency>
```

---

## JSON Schema Composition

yaml-core publishes JSON Schema fragments for each construct in `src/main/resources/schema/`:

| Fragment | What it defines |
|----------|----------------|
| `variable.schema.json` | `${prefix.name}` string pattern |
| `foreach.schema.json` | `forEach` field (inline or group reference) |
| `iterations.schema.json` | Named iteration groups |
| `if.schema.json` | `if` condition field |
| `module.schema.json` | Module declaration + parameters + outputs |
| `data.schema.json` | Typed CSV data sources |

Host applications compose these into their domain schema via `$ref`:

```json
{
  "properties": {
    "nodes": {
      "additionalProperties": {
        "properties": {
          "forEach": { "$ref": "classpath:schema/foreach.schema.json" },
          "if": { "$ref": "classpath:schema/if.schema.json" },
          "spec": { "type": "object" }
        }
      }
    },
    "iterations": { "$ref": "classpath:schema/iterations.schema.json" },
    "data": { "$ref": "classpath:schema/data.schema.json" },
    "imports": { "$ref": "classpath:schema/module.schema.json#/definitions/imports" }
  }
}
```

This gives you IDE validation, auto-complete, and documentation for the yaml-core constructs while keeping your domain-specific fields under your own schema control.

---

## Dependencies

| Artifact | When to use |
|----------|------------|
| `casehub-platform-yaml-core` | Always — the language primitives. Zero external dependencies, J2CL-transpilable |
| `casehub-platform-yaml-jackson` | When parsing YAML with Jackson — registers mixins for yaml-core types, enables dynamic section capture and case-insensitive enum deserialization |
| `casehub-platform-yaml-step-runtime` | When using step types — plugin catalog, step resolution, invoke handlers |
| `casehub-platform-yaml-codegen` | When generating Java records from JSON Schema — a Maven plugin, separate from the language runtime |
