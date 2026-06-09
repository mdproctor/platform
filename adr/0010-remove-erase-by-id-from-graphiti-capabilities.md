# 0010 — Remove ERASE_BY_ID from GraphitiCaseMemoryStore capabilities

Date: 2026-06-09
Status: Accepted

## Context and Problem Statement

`GraphitiCaseMemoryStore` declared `MemoryCapability.ERASE_BY_ID` and implemented
`eraseById()` by calling Graphiti's `DELETE /episode/{uuid}` REST endpoint. Investigation
(platform#74, platform#76) confirmed that this endpoint only removes the source `EpisodicNode`;
LLM-extracted `EntityNode` and `EntityEdge` records derived from that episode persist in the
knowledge graph. Callers who invoke `eraseById()` expecting complete deletion — particularly
for GDPR Art.17 right-to-erasure — receive a false success signal with data still present.

## Decision Drivers

* GDPR Art.17 compliance: callers must be able to rely on `eraseById()` actually erasing
* `MemoryCapability` is a contract: declaring a capability signals complete, correct support
* `eraseEntity()` (which calls `DELETE /group/{groupId}`) does achieve complete entity removal

## Considered Options

* **Option A** — Remove ERASE_BY_ID; eraseById() throws MemoryCapabilityException
* **Option B** — Keep ERASE_BY_ID; document the limitation in Javadoc
* **Option C** — Implement cascade: contribute `DELETE /episode/{uuid}?cascade=true` upstream

## Decision Outcome

Chosen option: **Option A**, because a capability declaration is a correctness contract and
partial deletion does not satisfy GDPR Art.17. Callers who need per-episode deletion must
use `eraseEntity()` (full entity wipe) or wait for upstream cascade support.

### Positive Consequences

* `capabilities()` accurately reflects what the adapter can do
* Callers that check capabilities first get an honest signal; unsafe callers that bypass
  the capability check get a clear exception instead of silent partial deletion
* Protocol PP-20260609-9b403d formalises this as a standing rule for all future adapters

### Negative Consequences / Tradeoffs

* Per-episode erasure is no longer available via Graphiti until upstream adds cascade
* Callers needing individual-memory deletion must use the coarser `eraseEntity()` (all memories
  for an entity are removed, not just one)

## Pros and Cons of the Options

### Option A — Remove ERASE_BY_ID; throw MemoryCapabilityException

* ✅ Honest capability contract — callers know not to rely on it
* ✅ Prevents silent GDPR Art.17 compliance failures
* ❌ Per-episode erasure unavailable until getzep/graphiti adds cascade support

### Option B — Keep ERASE_BY_ID; document limitation in Javadoc

* ✅ More functionality surfaced to callers
* ❌ Callers who check `ERASE_BY_ID` capability and call `eraseById()` receive false assurance
* ❌ Violates the MemoryCapability contract model — a declared capability means "fully supported"

### Option C — Implement cascade upstream (getzep/graphiti)

* ✅ Would make eraseById() fully correct
* ❌ Cannot be delivered in this session; Graphiti upstream timelines unknown
* ❌ Blocks correct behaviour on an external dependency

## Links

* platform#74 — investigation issue
* getzep/graphiti upstream — no cascade-delete endpoint as of 2026-06-09
* PP-20260609-9b403d — standing rule: ERASE_BY_ID requires complete erasure
