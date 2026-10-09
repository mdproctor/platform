# Stage 1 Remaining: CascadeRule Types + Registry Client

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> executing-plans to implement this plan task-by-task. Each task follows
> TDD (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** casehubio/platform#569
**Issue group:** casehubio/claudony#267 (epic)
**Depends on:** Stage 1 batches 1-4 (done)

**Goal:** Complete platform-only work: cascade rule types and a thin HTTP client for apps to register with the registry service.

**Architecture:** `CascadeRule` types are plain records in `platform-api`. The `registry-client` is a framework-agnostic HTTP client module — any app (Spring, Quarkus, plain Java) can use it to register/heartbeat/discover without importing the full SPI.

**Tech Stack:** Java 21, `java.net.http.HttpClient`

**Repo:** `casehub-platform`

## Global Constraints

- `CascadeRule` types have zero dependencies beyond Java SE
- `registry-client` uses only `java.net.http.HttpClient` — no framework deps
- Client is synchronous (blocking). Async variant can be added later if needed.

---

## Batch 5: CascadeRule Types

### Task 5: CascadeRule and CascadeAction in platform-api

**Files:**
- Create: `platform-api/src/main/java/io/casehub/platform/api/registry/CascadeRule.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/registry/CascadeAction.java`
- Test: `platform-api/src/test/java/io/casehub/platform/api/registry/CascadeRuleTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `CascadeRule`, `CascadeAction` — used by Stage 4 cascade handler

- [ ] **Step 1: Write tests**

```java
package io.casehub.platform.api.registry;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CascadeRuleTest {

    @Test
    void ruleFields() {
        var rule = new CascadeRule("app", "owns", CascadeAction.DEREGISTER);
        assertThat(rule.sourceType()).isEqualTo("app");
        assertThat(rule.relationshipType()).isEqualTo("owns");
        assertThat(rule.action()).isEqualTo(CascadeAction.DEREGISTER);
    }

    @Test
    void nullSourceTypeThrows() {
        assertThatThrownBy(() -> new CascadeRule(null, "owns", CascadeAction.DEREGISTER))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void allActions() {
        assertThat(CascadeAction.values())
            .containsExactly(CascadeAction.DEREGISTER, CascadeAction.MARK_DOWN, CascadeAction.NOTIFY_ONLY);
    }
}
```

- [ ] **Step 2: Run tests — verify fail**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl platform-api -Dtest=CascadeRuleTest -f ~/claude/casehub/platform/pom.xml`

- [ ] **Step 3: Implement**

`CascadeAction.java`:
```java
package io.casehub.platform.api.registry;

public enum CascadeAction {
    DEREGISTER,
    MARK_DOWN,
    NOTIFY_ONLY
}
```

`CascadeRule.java`:
```java
package io.casehub.platform.api.registry;

import java.util.Objects;

public record CascadeRule(
        String sourceType,
        String relationshipType,
        CascadeAction action
) {
    public CascadeRule {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(relationshipType, "relationshipType");
        Objects.requireNonNull(action, "action");
    }
}
```

- [ ] **Step 4: Run tests — verify pass**
- [ ] **Step 5: Commit**

```bash
git -C ~/claude/casehub/platform add platform-api/src/
git -C ~/claude/casehub/platform commit -m "feat(registry): add CascadeRule and CascadeAction types Refs #569"
```

---

## Batch 6: Registry Client

### Task 6: RegistryClient — thin HTTP client

**Files:**
- Create: `registry-client/pom.xml` (new module)
- Create: `registry-client/src/main/java/io/casehub/platform/registry/client/RegistryClient.java`
- Create: `registry-client/src/main/java/io/casehub/platform/registry/client/RegistryClientConfig.java`
- Modify: `pom.xml` (root — add module)
- Test: `registry-client/src/test/java/io/casehub/platform/registry/client/RegistryClientTest.java`

**Interfaces:**
- Consumes: `RegistryEntry`, `RegistryQuery`, `Relationship` (types from platform-api)
- Produces: `RegistryClient` — HTTP client that apps use to register with a remote registry service

- [ ] **Step 1: Create module POM**

Depends on `platform-api` (for types) and `jackson-databind` (for JSON serialization). No CDI, no Spring, no Quarkus.

- [ ] **Step 2: Write tests**

Use a lightweight HTTP server mock (e.g. `com.sun.net.httpserver.HttpServer` from the JDK) to verify the client sends correct requests.

Tests: register sends POST, heartbeat sends PUT, deregister sends DELETE, discover sends GET with query params, connection failure throws, timeout configurable.

- [ ] **Step 3: Implement RegistryClient**

```java
package io.casehub.platform.registry.client;

import io.casehub.platform.api.registry.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class RegistryClient {

    private final HttpClient http;
    private final URI baseUri;

    public RegistryClient(RegistryClientConfig config) {
        this.baseUri = config.baseUri();
        this.http = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .build();
    }

    public void register(RegistryEntry entry) { /* POST /api/registry */ }
    public void heartbeat(String id) { /* PUT /api/registry/{id}/heartbeat */ }
    public void deregister(String id) { /* DELETE /api/registry/{id} */ }
    public List<RegistryEntry> discover(RegistryQuery query) { /* GET /api/registry?type=&ns= */ }
    public void link(Relationship rel) { /* POST /api/registry/relationships */ }
    public List<Relationship> relationships(String id) { /* GET /api/registry/{id}/relationships */ }
}
```

- [ ] **Step 4: Run tests — verify pass**
- [ ] **Step 5: Commit**

```bash
git -C ~/claude/casehub/platform add registry-client/ pom.xml
git -C ~/claude/casehub/platform commit -m "feat(registry): add RegistryClient — thin HTTP client for remote registration Refs #569"
```

---

## References

- `2026-10-09-stage1-registry-core.md` — Stage 1 batches 1-4 (done)
- `platform-api/.../registry/RegistryEntry.java` — types the client serializes
- casehubio/platform#569 — issue
- casehubio/claudony#267 — parent epic
