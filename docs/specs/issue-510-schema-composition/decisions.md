## D1: Capability inheritance strategy

**Choice:** Recursive resolution via default method on PlaybookSchemaRegistry
**Alternatives:**
- Eager merge at registration — simpler queries but loses own-vs-inherited distinction, requires registration order
**Rationale:** Preserves introspection (can see what a domain adds vs inherits), works with any registration order (base must exist at resolution time, not registration time), recursion depth bounded by design (typically 2 levels: domain → built-in)
**Trade-offs:** Slightly more work at query time (recursive walk), but bounded and cacheable if needed
**Sources:** PlaybookSchemaRegistry.java, MapPlaybookSchemaRegistry.java, PlaybookSchemaDescriptor.java
**Exploration:** quick
**Status:** captured

## D2: Definition API change shape

**Choice:** Add `String capability` field directly as 7th record parameter, default null → "steps" in compact constructor
**Alternatives:**
- Static factory preserving old 6-arg signature — avoids updating callers but adds unnecessary API surface
**Rationale:** Pre-release API, breakage is acceptable. ~5 call sites to update in yaml-step-runtime. Builder gets `.capability(String)` method. Clean canonical constructor.
**Trade-offs:** Source-incompatible change for existing callers of the canonical constructor
**Sources:** Definition.java, AptPluginSource.java, PluginScanner.java, YamlStepDefinitionSource.java, McpToolSource.java, ScriptSource.java
**Exploration:** quick
**Status:** captured

## D3: SchemaFilteredRegistry module placement

**Choice:** yaml-core
**Alternatives:**
- yaml-step-runtime — near the Walker consumer, but CDI-coupled and less reusable
**Rationale:** yaml-core depends on yaml-plugin-api (where PluginRegistry lives) and owns all playbook schema types. Zero-dep, J2CL-safe. Natural home for the bridge between schemas and plugin registries.
**Trade-offs:** None significant — yaml-core already has the dependency
**Sources:** yaml-core/pom.xml, PlaybookSchemaRegistry.java, PluginRegistry.java
**Exploration:** quick
**Status:** captured

## D4: JSON Schema composition scope

**Choice:** Runtime-only via SchemaFilteredRegistry — no JSON Schema document composition in this issue
**Alternatives:**
- Full JSON Schema composition — generate composed schema docs per descriptor for IDE/offline validation. Significantly more scope.
**Rationale:** The SchemaFilteredRegistry + StepWalker already rejects unknown steps at parse time. The existing playbook.schema.json validates front matter. Runtime validation covers the core use case. JSON Schema composition for offline tooling is a natural follow-on.
**Trade-offs:** No IDE-level validation of step keys against schema until follow-on work
**Sources:** StepWalker.java (line 118-132), playbook.schema.json
**Exploration:** quick
**Status:** captured
