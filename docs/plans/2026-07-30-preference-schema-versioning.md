# Preference Schema Versioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a monotonic version counter to `PreferenceSchemaRegistry` SPI and use it as an HTTP ETag on `GET /preferences/schema` so UI editors can avoid redundant transfers via conditional GET (304 Not Modified).

**Architecture:** New `default long version()` method on the SPI (protocol: `spi-evolution-default-methods`). `InMemoryPreferenceSchemaRegistry` tracks version via `AtomicLong`, incremented on each `register()`. `PreferenceSchemaResource` uses JAX-RS `Request.evaluatePreconditions(EntityTag)` to return 304 when the client's `If-None-Match` matches.

**Tech Stack:** Java 21, Quarkus (JAX-RS), JUnit 5, RestAssured

## Global Constraints

- `platform-api/` must remain zero-dependency — no Quarkus, no JPA, no casehubio imports. Pure Java only.
- SPI evolution uses `default` methods with safe no-op returns (protocol: `spi-evolution-default-methods`).
- All commits linked to issue #198.

---

### Task 1: SPI Version Method + InMemory Counter + Unit Tests

**Files:**
- Modify: `platform-api/src/main/java/io/casehub/platform/api/preferences/PreferenceSchemaRegistry.java`
- Modify: `preferences-editor/src/main/java/io/casehub/platform/preferences/editor/InMemoryPreferenceSchemaRegistry.java`
- Modify: `../../preferences-editor-core/src/test/java/io/casehub/platform/preferences/editor/InMemoryPreferenceSchemaRegistryTest.java`

**Interfaces:**
- Consumes: nothing new
- Produces: `PreferenceSchemaRegistry.version()` returns `long` — used by Task 2's `PreferenceSchemaResource` to build ETag

- [ ] **Step 1: Write failing unit tests for version counter**

Add three tests to the existing `InMemoryPreferenceSchemaRegistryTest.java`:

```java
@Test
void version_starts_at_zero() {
    assertEquals(0L, registry.version());
}

@Test
void version_increments_on_register() {
    registry.register(PreferenceSchemaDescriptor.of(KEY_A).label("A").build());
    assertEquals(1L, registry.version());
    registry.register(PreferenceSchemaDescriptor.of(KEY_B).label("B").build());
    assertEquals(2L, registry.version());
}

@Test
void version_increments_on_overwrite() {
    registry.register(PreferenceSchemaDescriptor.of(KEY_A).label("First").build());
    assertEquals(1L, registry.version());
    registry.register(PreferenceSchemaDescriptor.of(KEY_A).label("Second").build());
    assertEquals(2L, registry.version());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl preferences-editor test -Dtest=InMemoryPreferenceSchemaRegistryTest -Dsurefire.failIfNoSpecifiedTests=false --batch-mode`

Expected: compilation failure — `version()` method does not exist on `PreferenceSchemaRegistry`.

- [ ] **Step 3: Add `default long version()` to SPI**

In `platform-api/src/main/java/io/casehub/platform/api/preferences/PreferenceSchemaRegistry.java`, add below the existing `discover()` method:

```java
default long version() { return 0L; }
```

The full interface becomes:

```java
package io.casehub.platform.api.preferences;

import java.util.Optional;
import java.util.Set;

public interface PreferenceSchemaRegistry {

    void register(PreferenceSchemaDescriptor descriptor);

    Optional<PreferenceSchemaDescriptor> resolve(String qualifiedName);

    Set<PreferenceSchemaDescriptor> discover();

    default long version() { return 0L; }
}
```

- [ ] **Step 4: Add AtomicLong counter to InMemoryPreferenceSchemaRegistry**

In `preferences-editor/src/main/java/io/casehub/platform/preferences/editor/InMemoryPreferenceSchemaRegistry.java`:

1. Add import: `import java.util.concurrent.atomic.AtomicLong;`
2. Add field: `private final AtomicLong version = new AtomicLong();`
3. In `register()`, add `version.incrementAndGet();` after the `entries.put()` call
4. Add override:
```java
@Override
public long version() {
    return version.get();
}
```

The full class becomes:

```java
package io.casehub.platform.preferences.editor;

import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;
import io.casehub.platform.api.preferences.PreferenceSchemaRegistry;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class InMemoryPreferenceSchemaRegistry implements PreferenceSchemaRegistry {

    private static final Logger LOG = Logger.getLogger(InMemoryPreferenceSchemaRegistry.class.getName());

    private final ConcurrentHashMap<String, PreferenceSchemaDescriptor> entries = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();

    @Override
    public void register(PreferenceSchemaDescriptor descriptor) {
        PreferenceSchemaDescriptor existing = entries.put(descriptor.qualifiedName(), descriptor);
        version.incrementAndGet();
        if (existing != null && !existing.equals(descriptor)) {
            LOG.log(Level.WARNING, "PreferenceSchemaDescriptor overwritten for ''{0}''", descriptor.qualifiedName());
        }
    }

    @Override
    public Optional<PreferenceSchemaDescriptor> resolve(String qualifiedName) {
        return Optional.ofNullable(entries.get(qualifiedName));
    }

    @Override
    public Set<PreferenceSchemaDescriptor> discover() {
        return Set.copyOf(entries.values());
    }

    @Override
    public long version() {
        return version.get();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -pl platform-api install --batch-mode -DskipTests && mvn -pl preferences-editor test -Dtest=InMemoryPreferenceSchemaRegistryTest --batch-mode`

Expected: all 8 tests pass (5 existing + 3 new).

- [ ] **Step 6: Verify NoOp inherits default**

Run: `mvn -pl platform test -Dtest=MockBeansTest --batch-mode`

Expected: all existing MockBeansTest tests pass. `NoOpPreferenceSchemaRegistry` inherits `default long version()` returning 0 without code changes.

- [ ] **Step 7: Commit**

```bash
git add platform-api/src/main/java/io/casehub/platform/api/preferences/PreferenceSchemaRegistry.java \
       preferences-editor/src/main/java/io/casehub/platform/preferences/editor/InMemoryPreferenceSchemaRegistry.java \
       preferences-editor/src/test/java/io/casehub/platform/preferences/editor/InMemoryPreferenceSchemaRegistryTest.java
git commit -m "feat(#198): PreferenceSchemaRegistry.version() — monotonic counter for schema versioning

Add default long version() to SPI (returns 0). InMemoryPreferenceSchemaRegistry
tracks registrations via AtomicLong, incremented on every register() call.

Refs: #198"
```

---

### Task 2: ETag Conditional GET on Schema Endpoint + Integration Tests

**Files:**
- Modify: `preferences-editor/src/main/java/io/casehub/platform/preferences/editor/PreferenceSchemaResource.java`
- Modify: `preferences-editor/src/test/java/io/casehub/platform/preferences/editor/PreferenceSchemaResourceTest.java`

**Interfaces:**
- Consumes: `PreferenceSchemaRegistry.version()` returning `long` (from Task 1)
- Produces: `GET /preferences/schema` returns `ETag` header; responds 304 when `If-None-Match` matches

- [ ] **Step 1: Write failing integration tests for ETag behavior**

Add five tests to the existing `PreferenceSchemaResourceTest.java`:

```java
@Test
void get_schema_returns_etag_header() {
    given()
    .when()
        .get("/preferences/schema")
    .then()
        .statusCode(200)
        .header("ETag", notNullValue());
}

@Test
void get_schema_with_matching_if_none_match_returns_304() {
    String etag = given()
        .when()
            .get("/preferences/schema")
        .then()
            .statusCode(200)
            .extract().header("ETag");

    given()
        .header("If-None-Match", etag)
    .when()
        .get("/preferences/schema")
    .then()
        .statusCode(304);
}

@Test
void get_schema_with_stale_if_none_match_returns_200() {
    given()
        .header("If-None-Match", "\"stale-value\"")
    .when()
        .get("/preferences/schema")
    .then()
        .statusCode(200)
        .header("ETag", notNullValue())
        .body("size()", greaterThanOrEqualTo(3));
}

@Test
void get_schema_without_if_none_match_returns_200_with_etag() {
    given()
    .when()
        .get("/preferences/schema")
    .then()
        .statusCode(200)
        .header("ETag", notNullValue())
        .body("size()", greaterThanOrEqualTo(3));
}

@Test
void etag_is_same_regardless_of_namespace_filter() {
    String etagAll = given()
        .when()
            .get("/preferences/schema")
        .then()
            .statusCode(200)
            .extract().header("ETag");

    String etagFiltered = given()
        .queryParam("namespace", "casehub.work")
    .when()
        .get("/preferences/schema")
    .then()
        .statusCode(200)
        .extract().header("ETag");

    assertEquals(etagAll, etagFiltered);
}
```

Add the `assertEquals` static import at the top of the file:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -pl platform-api install --batch-mode -DskipTests && mvn -pl preferences-editor test -Dtest=PreferenceSchemaResourceTest --batch-mode`

Expected: `get_schema_returns_etag_header` fails — no ETag header present. The 304 test fails — endpoint always returns 200.

- [ ] **Step 3: Implement conditional GET in PreferenceSchemaResource**

Replace the entire `PreferenceSchemaResource.java` with:

```java
package io.casehub.platform.preferences.editor;

import io.casehub.platform.api.preferences.PreferenceSchemaDescriptor;
import io.casehub.platform.api.preferences.PreferenceSchemaRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;

import java.util.Comparator;
import java.util.List;

@ApplicationScoped
@Path("/preferences/schema")
public class PreferenceSchemaResource {

    @Inject PreferenceSchemaRegistry registry;

    @GET
    public Response schema(@QueryParam("namespace") String namespace,
                           @Context Request request) {
        EntityTag etag = new EntityTag(String.valueOf(registry.version()));
        Response.ResponseBuilder notModified = request.evaluatePreconditions(etag);
        if (notModified != null) {
            return notModified.build();
        }
        List<PreferenceSchemaDescriptor> result = registry.discover().stream()
                .filter(d -> namespace == null || namespace.isBlank() || d.namespace().equals(namespace))
                .sorted(Comparator.comparing(PreferenceSchemaDescriptor::qualifiedName))
                .toList();
        return Response.ok(result).tag(etag).build();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -pl platform-api install --batch-mode -DskipTests && mvn -pl preferences-editor test -Dtest=PreferenceSchemaResourceTest --batch-mode`

Expected: all 13 tests pass (8 existing + 5 new).

- [ ] **Step 5: Run full module test suite**

Run: `mvn -pl platform-api install --batch-mode -DskipTests && mvn -pl preferences-editor test --batch-mode`

Expected: all tests in the module pass — no regressions.

- [ ] **Step 6: Commit**

```bash
git add preferences-editor/src/main/java/io/casehub/platform/preferences/editor/PreferenceSchemaResource.java \
       preferences-editor/src/test/java/io/casehub/platform/preferences/editor/PreferenceSchemaResourceTest.java
git commit -m "feat(#198): ETag conditional GET on /preferences/schema

Return ETag header derived from PreferenceSchemaRegistry.version(). Clients
sending If-None-Match with matching value get 304 Not Modified. One ETag for
the entire schema regardless of namespace filter.

Refs: #198"
```
