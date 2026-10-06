---
title: "The file declares what it is — schema composition for playbook YAML"
date: 2026-10-06
author: mdp
entry_type: note
subtype: diary
tags: [yaml, schema, composition, plugin-api, yaml-core]
projects: [casehub-platform]
refs:
  - "casehubio/platform#510"
---

The playbook infrastructure landed in the previous session — front matter parsing, schema registry, capability model. A playbook declares `schema: clinical-server` in its front matter and the registry resolves it. But nothing actually *used* that declaration. Every playbook got the same step vocabulary regardless of its schema. The composition mechanism was the missing layer.

Three things needed connecting. Schemas define capabilities (what a playbook can do). Plugins belong to capabilities (which steps are available). The registry filters by schema (what the walker accepts). Each one is simple; the value is in how they compose.

## Capability inheritance

A domain schema like `clinical-server` extends `server` — it should inherit all of server's capabilities (steps, orchestration, correlation, etc.) plus its own domain-specific ones. The `PlaybookSchemaDescriptor` already stored a `baseSchema` field but nothing walked the chain.

I added `effectiveCapabilities()` as a default method on `PlaybookSchemaRegistry`. It iterates up the base chain, collecting capabilities at each level, with a visited set for cycle detection. Terminates when `baseSchema()` returns null — which is always the case for built-in schemas.

The alternative was eager merge at registration time — merge the base capabilities into the domain descriptor when `register()` is called. Simpler at query time, but you lose the ability to introspect which capabilities are the domain's own contribution versus inherited. The iterative walk preserves that distinction and doesn't require registration order.

## Plugin capability binding

`@Plugin` and `Definition` both gained a `capability` field. Default is `"steps"` — the base capability every schema includes. A plugin that belongs to a specific capability declares it:

```java
@Plugin(value = "clinical-enroll", capability = "clinical-trial")
public record ClinicalEnrollPlugin(@Required String patientId) { ... }
```

This is a public API change to `Definition` — adding a 7th record parameter. Pre-release, so the breakage is acceptable. Five production sources and eight test files needed updating. The compact constructor defaults null to `"steps"`, so existing code that passes null gets the right default.

The annotation processor now includes `capability` in the generated manifest JSON, and `AptPluginSource` reads it back when loading plugins from classpath.

## Schema-filtered registry

`SchemaFilteredRegistry` wraps any `PluginRegistry` and filters by a schema's effective capabilities. The `forSchema()` factory bridges the schema registry and plugin registry — resolve the schema's capabilities once, then filter every `resolve()` call against them.

```java
var filtered = SchemaFilteredRegistry.forSchema(registry, schemas, "clinical-server");
filtered.resolve("rest-call");         // present — steps capability, in server
filtered.resolve("clinical-enroll");   // present — clinical-trial capability, in domain
filtered.resolve("spotlight");         // empty — spotlight capability, client-only
```

The `availableActions()` override is the subtle part. When StepWalker rejects an unknown key, it lists all available actions in the error message. With the filter, that error message only shows steps valid for the current schema — so the feedback is actionable rather than overwhelming.

## What this opens up

The runtime wiring — where `PlaybookParser.parse()` produces a `PlaybookDocument`, the schema is resolved, and a `SchemaFilteredRegistry` is passed to `StepWalker` — is a natural follow-on. The mechanism is fully testable without CDI today; the CDI integration just passes a different registry instance.

JSON Schema composition for offline IDE validation is the other follow-on. Right now the runtime rejects bad steps at parse time. Generating a composed JSON Schema per schema descriptor would push that validation into the editor, before the playbook ever runs.
