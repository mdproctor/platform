# DataSource + Alpha Network Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the DataSource SPI, alpha network, in-memory registry, and CDI bridge for event routing in casehub-platform.

**Architecture:** SPI types in `platform-api/` (zero-dep), `@DefaultBean` no-op in `platform/`, `@Alternative @Priority(100)` in-memory implementation in `datasource-inmem/` containing the alpha network (type nodes, filter nodes, fan-out). `DataSourceRouter` in `platform/` bridges CDI `CloudEvent` events into DataSources. Follows the exact pattern of `endpoints-memory/` and `InMemoryEndpointRegistry`.

**Tech Stack:** Java 21, Quarkus CDI, CloudEvents SDK, jackson-jq (existing), JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-07-03-datasource-alpha-network-architecture-design.md`

## Global Constraints

- `platform-api/` must remain zero-dependency — no Quarkus, no JPA, no casehubio imports. Pure Java only.
- Every SPI in `platform-api/` gets a `@DefaultBean` implementation in `platform/`.
- `@Alternative @Priority(100)` for in-memory implementations (Tier 4 CDI priority).
- No `quarkus:build` goal in `datasource-inmem/`.
- All new types follow existing package naming: `io.casehub.platform.api.datasource` for SPI, `io.casehub.platform.datasource` for `@DefaultBean`, `io.casehub.platform.datasource.memory` for in-memory impl.
- TDD — test first, implement second, commit after each task.
- Every commit references an issue. Create the GitHub issue before the first commit.

## Scope

**In scope:** SPI types, NoOp registry, in-memory registry with alpha network, DataSourceRouter CDI bridge, unit tests.

**Out of scope:** Engine `DataSourceTrigger`, case-scoped DataSources, MVEL3 real evaluator, marshaller configuration model, RAS/Engine consumer migration. These are follow-up issues.

---

### Task 1: Create GitHub issue + SPI types in platform-api

**Files:**
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/ObjectType.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/ClassObjectType.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/DataProcessor.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/SubscriptionHandle.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/FilterExpression.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/DataSource.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/DataSourceDescriptor.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/DataSourceQuery.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/DataSourceRegistered.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/DataSourceRegistry.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/Marshaller.java`
- Create: `platform-api/src/main/java/io/casehub/platform/api/datasource/MarshalException.java`
- Test: `platform-api/src/test/java/io/casehub/platform/api/datasource/ClassObjectTypeTest.java`
- Test: `platform-api/src/test/java/io/casehub/platform/api/datasource/FilterExpressionTest.java`
- Test: `platform-api/src/test/java/io/casehub/platform/api/datasource/DataSourceDescriptorTest.java`
- Test: `platform-api/src/test/java/io/casehub/platform/api/datasource/DataSourceRegisteredTest.java`

**Interfaces:**
- Consumes: `io.casehub.platform.api.path.Path`, `io.casehub.platform.api.identity.TenancyConstants`
- Produces: All SPI types consumed by Tasks 2, 3, and 4

- [ ] **Step 1: Create GitHub issue**

```bash
gh issue create --repo casehubio/platform --title "feat: DataSource SPI + alpha network — event routing infrastructure" --body "## Context

Implements the DataSource + Alpha Network architecture spec.

**Spec:** docs/superpowers/specs/2026-07-03-datasource-alpha-network-architecture-design.md

## Scope

- SPI types in platform-api/ (ObjectType, DataProcessor, DataSource, DataSourceRegistry, etc.)
- NoOpDataSourceRegistry @DefaultBean in platform/
- InMemoryDataSourceRegistry @Alternative @Priority(100) in datasource-inmem/
- DataSourceRouter CDI bridge in platform/
- Alpha network implementation (TypeNode, FilterNode, FanOutProcessor)

## Out of scope (follow-up issues)

- Engine DataSourceTrigger
- Case-scoped DataSources
- MVEL3 real evaluator (mock only)
- Marshaller configuration model
- RAS/Engine consumer migration"
```

Record the issue number for all subsequent commits.

- [ ] **Step 2: Write ObjectType interface**

```java
package io.casehub.platform.api.datasource;

public interface ObjectType<T> {
    boolean matches(Object object);
    Object getTypeKey();
}
```

- [ ] **Step 3: Write ClassObjectType + test**

Test first:

```java
package io.casehub.platform.api.datasource;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClassObjectTypeTest {

    @Test
    void matches_exactType() {
        ClassObjectType<String> type = new ClassObjectType<>(String.class);
        assertThat(type.matches("hello")).isTrue();
        assertThat(type.matches(42)).isFalse();
        assertThat(type.matches(null)).isFalse();
    }

    @Test
    void matches_subtype() {
        ClassObjectType<Number> type = new ClassObjectType<>(Number.class);
        assertThat(type.matches(42)).isTrue();
        assertThat(type.matches(3.14)).isTrue();
        assertThat(type.matches("not a number")).isFalse();
    }

    @Test
    void getTypeKey_returnsClass() {
        ClassObjectType<String> type = new ClassObjectType<>(String.class);
        assertThat(type.getTypeKey()).isEqualTo(String.class);
    }

    @Test
    void equals_sameClass() {
        ClassObjectType<String> a = new ClassObjectType<>(String.class);
        ClassObjectType<String> b = new ClassObjectType<>(String.class);
        assertThat(a.getTypeKey()).isEqualTo(b.getTypeKey());
    }
}
```

Implementation:

```java
package io.casehub.platform.api.datasource;

import java.util.Objects;

public final class ClassObjectType<T> implements ObjectType<T> {

    private final Class<T> clazz;

    public ClassObjectType(Class<T> clazz) {
        this.clazz = Objects.requireNonNull(clazz, "clazz");
    }

    @Override
    public boolean matches(Object object) {
        return object != null && clazz.isInstance(object);
    }

    @Override
    public Object getTypeKey() {
        return clazz;
    }

    public Class<T> getObjectClass() {
        return clazz;
    }
}
```

- [ ] **Step 4: Write DataProcessor, SubscriptionHandle, MarshalException, Marshaller**

```java
package io.casehub.platform.api.datasource;

public interface DataProcessor<T> {
    void add(T object);
}
```

```java
package io.casehub.platform.api.datasource;

public interface SubscriptionHandle {
    void unsubscribe();
    boolean isActive();
}
```

```java
package io.casehub.platform.api.datasource;

public class MarshalException extends Exception {
    public MarshalException(String message) { super(message); }
    public MarshalException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package io.casehub.platform.api.datasource;

@FunctionalInterface
public interface Marshaller<I, O> {
    O marshal(I input) throws MarshalException;
}
```

- [ ] **Step 5: Write FilterExpression + test**

Test first:

```java
package io.casehub.platform.api.datasource;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterExpressionTest {

    @Test
    void test_delegatesToPredicate() {
        FilterExpression<Integer> expr = new FilterExpression<>("jq", ". > 5", i -> i > 5);
        assertThat(expr.test(10)).isTrue();
        assertThat(expr.test(3)).isFalse();
    }

    @Test
    void accessors() {
        FilterExpression<String> expr = new FilterExpression<>("mvel", "name != null", s -> s != null);
        assertThat(expr.type()).isEqualTo("mvel");
        assertThat(expr.expression()).isEqualTo("name != null");
    }

    @Test
    void nullsRejected() {
        assertThatThrownBy(() -> new FilterExpression<>(null, "expr", s -> true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FilterExpression<>("jq", null, s -> true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FilterExpression<>("jq", "expr", null))
                .isInstanceOf(NullPointerException.class);
    }
}
```

Implementation:

```java
package io.casehub.platform.api.datasource;

import java.util.Objects;
import java.util.function.Predicate;

public record FilterExpression<T>(
        String type,
        String expression,
        Predicate<T> predicate
) implements Predicate<T> {

    public FilterExpression {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public boolean test(T t) {
        return predicate.test(t);
    }
}
```

- [ ] **Step 6: Write DataSource interface**

```java
package io.casehub.platform.api.datasource;

import java.util.function.Predicate;

public interface DataSource<T> extends DataProcessor<T> {

    SubscriptionHandle subscribe(DataProcessor<? super T> processor);

    <U> SubscriptionHandle subscribe(ObjectType<U> objectType, DataProcessor<? super U> processor);

    <U> SubscriptionHandle subscribe(ObjectType<U> objectType, Predicate<U> filter, DataProcessor<? super U> processor);

    <U> SubscriptionHandle subscribe(Class<U> type, Predicate<U> filter, DataProcessor<? super U> processor);
}
```

- [ ] **Step 7: Write DataSourceDescriptor + test**

Test first:

```java
package io.casehub.platform.api.datasource;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceDescriptorTest {

    @Test
    void immutableCopies() {
        var mutableProps = new java.util.HashMap<String, String>();
        mutableProps.put("key", "value");
        var mutableTypes = new java.util.HashSet<String>();
        mutableTypes.add("io.casehub.siem.alert");

        DataSourceDescriptor desc = new DataSourceDescriptor(
                Path.parse("siem/alerts"), "tenant-1",
                new ClassObjectType<>(String.class), null,
                mutableTypes, mutableProps);

        mutableProps.put("other", "val");
        mutableTypes.add("io.casehub.other");
        assertThat(desc.properties()).hasSize(1);
        assertThat(desc.acceptedEventTypes()).hasSize(1);
    }

    @Test
    void isPlatformGlobal() {
        DataSourceDescriptor global = new DataSourceDescriptor(
                Path.parse("global"), TenancyConstants.PLATFORM_TENANT_ID,
                new ClassObjectType<>(Object.class), null,
                Set.of(), Map.of());
        DataSourceDescriptor tenant = new DataSourceDescriptor(
                Path.parse("tenant"), "t1",
                new ClassObjectType<>(Object.class), null,
                Set.of(), Map.of());
        assertThat(global.isPlatformGlobal()).isTrue();
        assertThat(tenant.isPlatformGlobal()).isFalse();
    }

    @Test
    void nullsRejected() {
        assertThatThrownBy(() -> new DataSourceDescriptor(
                null, "t", new ClassObjectType<>(Object.class), null, Set.of(), Map.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
```

Implementation:

```java
package io.casehub.platform.api.datasource;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record DataSourceDescriptor(
        Path path,
        String tenancyId,
        ObjectType<?> objectType,
        Path endpointPath,
        Set<String> acceptedEventTypes,
        Map<String, String> properties
) {
    public DataSourceDescriptor {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(acceptedEventTypes, "acceptedEventTypes");
        Objects.requireNonNull(properties, "properties");
        acceptedEventTypes = Set.copyOf(acceptedEventTypes);
        properties = Map.copyOf(properties);
    }

    public boolean isPlatformGlobal() {
        return TenancyConstants.PLATFORM_TENANT_ID.equals(tenancyId);
    }
}
```

- [ ] **Step 8: Write DataSourceQuery, DataSourceRegistered + test, DataSourceRegistry**

```java
package io.casehub.platform.api.datasource;

import java.util.Objects;

public record DataSourceQuery(
        String tenancyId,
        ObjectType<?> objectType
) {
    public DataSourceQuery {
        Objects.requireNonNull(tenancyId, "tenancyId");
    }
}
```

```java
package io.casehub.platform.api.datasource;

import java.util.Objects;

public record DataSourceRegistered(DataSourceDescriptor descriptor) {
    public DataSourceRegistered {
        Objects.requireNonNull(descriptor, "descriptor");
    }
}
```

Test for DataSourceRegistered:

```java
package io.casehub.platform.api.datasource;

import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceRegisteredTest {

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new DataSourceRegistered(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void holdsDescriptor() {
        var desc = new DataSourceDescriptor(
                Path.parse("test"), "t1",
                new ClassObjectType<>(Object.class), null,
                Set.of(), Map.of());
        var event = new DataSourceRegistered(desc);
        assertThat(event.descriptor()).isSameAs(desc);
    }
}
```

DataSourceRegistry interface:

```java
package io.casehub.platform.api.datasource;

import io.casehub.platform.api.path.Path;
import java.util.List;
import java.util.Optional;

public interface DataSourceRegistry {
    DataSource<?> register(DataSourceDescriptor descriptor);
    Optional<DataSourceDescriptor> resolve(Path path, String tenancyId);
    Optional<DataSource<?>> resolveSource(Path path, String tenancyId);
    List<DataSourceDescriptor> discover(DataSourceQuery query);
    void deregister(Path path, String tenancyId);
}
```

- [ ] **Step 9: Run `mvn --batch-mode install` from platform root to verify compilation**

Expected: BUILD SUCCESS — all new types compile, all existing tests still pass.

- [ ] **Step 10: Commit**

```bash
git add platform-api/src/main/java/io/casehub/platform/api/datasource/ platform-api/src/test/java/io/casehub/platform/api/datasource/
git commit -m "feat(platform#NNN): DataSource SPI types in platform-api — ObjectType, DataProcessor, DataSource, DataSourceRegistry"
```

---

### Task 2: NoOpDataSourceRegistry in platform/

**Files:**
- Create: `platform/src/main/java/io/casehub/platform/datasource/NoOpDataSourceRegistry.java`
- Test: `platform/src/test/java/io/casehub/platform/datasource/NoOpDataSourceRegistryTest.java`

**Interfaces:**
- Consumes: `DataSourceRegistry`, `DataSourceDescriptor`, `DataSourceQuery`, `DataSource` from Task 1
- Produces: `NoOpDataSourceRegistry @DefaultBean` — active when no backend on classpath

- [ ] **Step 1: Write test**

```java
package io.casehub.platform.datasource;

import io.casehub.platform.api.datasource.ClassObjectType;
import io.casehub.platform.api.datasource.DataSourceDescriptor;
import io.casehub.platform.api.datasource.DataSourceQuery;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class NoOpDataSourceRegistryTest {

    private final NoOpDataSourceRegistry registry = new NoOpDataSourceRegistry();

    @Test
    void register_returnsNull() {
        var desc = new DataSourceDescriptor(
                Path.parse("test"), "t1",
                new ClassObjectType<>(Object.class), null,
                Set.of(), Map.of());
        assertThat(registry.register(desc)).isNull();
    }

    @Test
    void resolve_alwaysEmpty() {
        assertThat(registry.resolve(Path.parse("any"), "t1")).isEmpty();
    }

    @Test
    void resolveSource_alwaysEmpty() {
        assertThat(registry.resolveSource(Path.parse("any"), "t1")).isEmpty();
    }

    @Test
    void discover_alwaysEmpty() {
        assertThat(registry.discover(new DataSourceQuery("t1", null))).isEmpty();
    }

    @Test
    void deregister_noOp() {
        registry.deregister(Path.parse("any"), "t1");
    }
}
```

- [ ] **Step 2: Write implementation**

```java
package io.casehub.platform.datasource;

import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceDescriptor;
import io.casehub.platform.api.datasource.DataSourceQuery;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.path.Path;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

@DefaultBean
@ApplicationScoped
public class NoOpDataSourceRegistry implements DataSourceRegistry {

    @Override public DataSource<?> register(DataSourceDescriptor descriptor) { return null; }

    @Override
    public Optional<DataSourceDescriptor> resolve(Path path, String tenancyId) {
        return Optional.empty();
    }

    @Override
    public Optional<DataSource<?>> resolveSource(Path path, String tenancyId) {
        return Optional.empty();
    }

    @Override
    public List<DataSourceDescriptor> discover(DataSourceQuery query) {
        return List.of();
    }

    @Override public void deregister(Path path, String tenancyId) {}
}
```

- [ ] **Step 3: Run tests, verify pass**

Run: `mvn --batch-mode test -pl platform`

- [ ] **Step 4: Commit**

```bash
git add platform/src/main/java/io/casehub/platform/datasource/ platform/src/test/java/io/casehub/platform/datasource/
git commit -m "feat(platform#NNN): NoOpDataSourceRegistry @DefaultBean"
```

---

### Task 3: datasource-inmem module — InMemoryDataSourceRegistry + alpha network

**Files:**
- Create: `datasource-inmem/pom.xml`
- Create: `datasource-inmem/src/main/java/io/casehub/platform/datasource/memory/RegistryKey.java`
- Create: `datasource-inmem/src/main/java/io/casehub/platform/datasource/memory/AlphaDataSource.java`
- Create: `datasource-inmem/src/main/java/io/casehub/platform/datasource/memory/TypeNode.java`
- Create: `datasource-inmem/src/main/java/io/casehub/platform/datasource/memory/FilterNode.java`
- Create: `datasource-inmem/src/main/java/io/casehub/platform/datasource/memory/FanOutProcessor.java`
- Create: `datasource-inmem/src/main/java/io/casehub/platform/datasource/memory/InMemoryDataSourceRegistry.java`
- Modify: `pom.xml` (root) — add `datasource-inmem` to `<modules>`
- Test: `../../datasource-inmem-core/src/test/java/io/casehub/platform/datasource/memory/AlphaDataSourceTest.java`
- Test: `../../datasource-inmem-core/src/test/java/io/casehub/platform/datasource/memory/InMemoryDataSourceRegistryTest.java`

**Interfaces:**
- Consumes: All SPI types from Task 1
- Produces: `InMemoryDataSourceRegistry @Alternative @Priority(100)`, `AlphaDataSource<T>` implementing `DataSource<T>` with alpha network internals

- [ ] **Step 1: Create module pom.xml**

Model on `endpoints-memory/pom.xml`. Dependencies: `casehub-platform-api` (compile), JUnit/AssertJ (test). No Quarkus runtime dep except `quarkus-arc` for CDI annotations. No `quarkus:build` goal.

- [ ] **Step 2: Add module to root pom.xml**

Add `<module>datasource-inmem</module>` to the `<modules>` section in the root `pom.xml`.

- [ ] **Step 3: Write RegistryKey**

```java
package io.casehub.platform.datasource.memory;

record RegistryKey(String path, String tenancyId) {}
```

Same pattern as `endpoints-memory/RegistryKey`.

- [ ] **Step 4: Write alpha network tests — type discrimination + filter + fan-out**

```java
package io.casehub.platform.datasource.memory;

import io.casehub.platform.api.datasource.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AlphaDataSourceTest {

    @Test
    void subscribe_allEvents_receivesEverything() {
        AlphaDataSource<Object> ds = new AlphaDataSource<>();
        List<Object> received = new ArrayList<>();
        ds.subscribe(received::add);

        ds.add("hello");
        ds.add(42);
        assertThat(received).containsExactly("hello", 42);
    }

    @Test
    void subscribe_withTypeFilter_onlyMatchingType() {
        AlphaDataSource<Object> ds = new AlphaDataSource<>();
        List<String> strings = new ArrayList<>();
        ds.subscribe(new ClassObjectType<>(String.class), (DataProcessor<String>) strings::add);

        ds.add("hello");
        ds.add(42);
        ds.add("world");
        assertThat(strings).containsExactly("hello", "world");
    }

    @Test
    void subscribe_withTypeAndFilter_appliesBoth() {
        AlphaDataSource<Object> ds = new AlphaDataSource<>();
        List<Integer> large = new ArrayList<>();
        ds.subscribe(new ClassObjectType<>(Integer.class), i -> i > 10, (DataProcessor<Integer>) large::add);

        ds.add(5);
        ds.add(20);
        ds.add("not a number");
        ds.add(15);
        assertThat(large).containsExactly(20, 15);
    }

    @Test
    void subscribe_classConvenience_works() {
        AlphaDataSource<Object> ds = new AlphaDataSource<>();
        List<String> strings = new ArrayList<>();
        ds.subscribe(String.class, s -> s.length() > 3, (DataProcessor<String>) strings::add);

        ds.add("hi");
        ds.add("hello");
        ds.add(42);
        assertThat(strings).containsExactly("hello");
    }

    @Test
    void typeNode_shared_acrossSubscriptions() {
        AlphaDataSource<Object> ds = new AlphaDataSource<>();
        List<String> list1 = new ArrayList<>();
        List<String> list2 = new ArrayList<>();
        ds.subscribe(new ClassObjectType<>(String.class), (DataProcessor<String>) list1::add);
        ds.subscribe(new ClassObjectType<>(String.class), (DataProcessor<String>) list2::add);

        ds.add("shared");
        assertThat(list1).containsExactly("shared");
        assertThat(list2).containsExactly("shared");
    }

    @Test
    void filterExpression_shared_sameTypeAndExpression() {
        AlphaDataSource<Object> ds = new AlphaDataSource<>();
        List<Integer> list1 = new ArrayList<>();
        List<Integer> list2 = new ArrayList<>();
        FilterExpression<Integer> expr1 = new FilterExpression<>("jq", ". > 10", i -> i > 10);
        FilterExpression<Integer> expr2 = new FilterExpression<>("jq", ". > 10", i -> i > 10);
        ds.subscribe(new ClassObjectType<>(Integer.class), expr1, (DataProcessor<Integer>) list1::add);
        ds.subscribe(new ClassObjectType<>(Integer.class), expr2, (DataProcessor<Integer>) list2::add);

        ds.add(20);
        assertThat(list1).containsExactly(20);
        assertThat(list2).containsExactly(20);
    }

    @Test
    void unsubscribe_stopsDelivery() {
        AlphaDataSource<Object> ds = new AlphaDataSource<>();
        List<Object> received = new ArrayList<>();
        SubscriptionHandle handle = ds.subscribe(received::add);

        ds.add("before");
        handle.unsubscribe();
        ds.add("after");
        assertThat(received).containsExactly("before");
        assertThat(handle.isActive()).isFalse();
    }

    @Test
    void subscriberException_doesNotBlockOthers() {
        AlphaDataSource<Object> ds = new AlphaDataSource<>();
        List<Object> good = new ArrayList<>();
        ds.subscribe(o -> { throw new RuntimeException("boom"); });
        ds.subscribe(good::add);

        ds.add("test");
        assertThat(good).containsExactly("test");
    }
}
```

- [ ] **Step 5: Implement AlphaDataSource, TypeNode, FilterNode, FanOutProcessor**

`AlphaDataSource<T>` implements `DataSource<T>`. On `add(T)`:
1. Direct subscribers (no type filter) get the event
2. For each type node, check `objectType.matches(object)` — if match, propagate to filter chain
3. Filter chain evaluates predicates — if pass, deliver to subscriber
4. Fan-out: multiple subscribers on same type+filter get the event

Type nodes are keyed by `ObjectType.getTypeKey()` in a `HashMap`. Shared automatically.

Filter nodes check `instanceof FilterExpression` — if both are `FilterExpression` with same `type()` + `expression()`, share the node. Otherwise each gets its own.

Per-subscriber error isolation: each `DataProcessor.add()` wrapped in try/catch, WARN log on exception.

- [ ] **Step 6: Run tests, verify pass**

Run: `mvn --batch-mode test -pl datasource-inmem`

- [ ] **Step 7: Write InMemoryDataSourceRegistry + test**

Test:

```java
package io.casehub.platform.datasource.memory;

import io.casehub.platform.api.datasource.*;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDataSourceRegistryTest {

    private final InMemoryDataSourceRegistry registry = new InMemoryDataSourceRegistry();

    private DataSourceDescriptor descriptor(String path, String tenancyId) {
        return new DataSourceDescriptor(
                Path.parse(path), tenancyId,
                new ClassObjectType<>(Object.class), null,
                Set.of(), Map.of());
    }

    @Test
    void register_returnsDataSource() {
        DataSource<?> ds = registry.register(descriptor("test", "t1"));
        assertThat(ds).isNotNull();
    }

    @Test
    void resolve_tenantSpecific() {
        registry.register(descriptor("test", "t1"));
        assertThat(registry.resolve(Path.parse("test"), "t1")).isPresent();
        assertThat(registry.resolve(Path.parse("test"), "t2")).isEmpty();
    }

    @Test
    void resolve_platformGlobalFallback() {
        registry.register(descriptor("global", TenancyConstants.PLATFORM_TENANT_ID));
        assertThat(registry.resolve(Path.parse("global"), "any-tenant")).isPresent();
    }

    @Test
    void resolve_tenantOverridesPlatform() {
        registry.register(descriptor("path", TenancyConstants.PLATFORM_TENANT_ID));
        registry.register(descriptor("path", "t1"));
        var result = registry.resolve(Path.parse("path"), "t1");
        assertThat(result).isPresent();
        assertThat(result.get().tenancyId()).isEqualTo("t1");
    }

    @Test
    void resolveSource_returnsRuntime() {
        registry.register(descriptor("test", "t1"));
        assertThat(registry.resolveSource(Path.parse("test"), "t1")).isPresent();
    }

    @Test
    void discover_includesPlatformGlobal() {
        registry.register(descriptor("a", "t1"));
        registry.register(descriptor("b", TenancyConstants.PLATFORM_TENANT_ID));
        var results = registry.discover(new DataSourceQuery("t1", null));
        assertThat(results).hasSize(2);
    }

    @Test
    void discover_excludesCrossTenant() {
        registry.register(descriptor("a", "t1"));
        registry.register(descriptor("b", "t2"));
        var results = registry.discover(new DataSourceQuery("t1", null));
        assertThat(results).hasSize(1);
    }

    @Test
    void deregister_removesBoth() {
        registry.register(descriptor("test", "t1"));
        registry.deregister(Path.parse("test"), "t1");
        assertThat(registry.resolve(Path.parse("test"), "t1")).isEmpty();
        assertThat(registry.resolveSource(Path.parse("test"), "t1")).isEmpty();
    }
}
```

Implementation follows `InMemoryEndpointRegistry` pattern: `ConcurrentHashMap<RegistryKey, ...>`, `@Alternative @Priority(100)`, CDI event firing on register. Package-private no-arg constructor for unit tests.

- [ ] **Step 8: Run full build**

Run: `mvn --batch-mode install`

- [ ] **Step 9: Commit**

```bash
git add datasource-inmem/ pom.xml
git commit -m "feat(platform#NNN): InMemoryDataSourceRegistry + alpha network — type discrimination, filter chains, fan-out"
```

---

### Task 4: DataSourceRouter CDI bridge in platform/

**Files:**
- Create: `platform/src/main/java/io/casehub/platform/datasource/DataSourceRouter.java`
- Test: `../../datasource-inmem/src/test/java/io/casehub/platform/datasource/memory/DataSourceRouterTest.java`

**Interfaces:**
- Consumes: `DataSourceRegistry` (injected), `DataSourceRegistered` (CDI event), `CloudEvent` (CDI event), `DataSourceDescriptor.acceptedEventTypes()`
- Produces: CDI-to-DataSource bridge — pushes CloudEvents into registered DataSources

Note: The router test lives in `datasource-inmem/` because it needs a working `DataSourceRegistry` implementation. The router itself lives in `platform/` and injects the SPI.

- [ ] **Step 1: Write test**

```java
package io.casehub.platform.datasource.memory;

import io.casehub.platform.api.datasource.*;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.datasource.DataSourceRouter;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class DataSourceRouterTest {

    private InMemoryDataSourceRegistry registry;
    private DataSourceRouter router;

    @BeforeEach
    void setUp() {
        registry = new InMemoryDataSourceRegistry();
        router = new DataSourceRouter(registry);
    }

    private CloudEvent cloudEvent(String type, String tenancyId) {
        var builder = CloudEventBuilder.v1()
                .withId("test-1")
                .withSource(URI.create("/test"))
                .withType(type);
        if (tenancyId != null) {
            builder.withExtension("tenancyid", tenancyId);
        }
        return builder.build();
    }

    @Test
    void routesToMatchingTenantDataSource() {
        DataSource<?> ds = registry.register(new DataSourceDescriptor(
                Path.parse("siem"), "t1",
                new ClassObjectType<>(CloudEvent.class), null,
                Set.of(), Map.of()));
        List<Object> received = new ArrayList<>();
        ds.subscribe(received::add);

        router.onStartup(null);
        router.onCloudEvent(cloudEvent("siem.alert", "t1"));

        assertThat(received).hasSize(1);
    }

    @Test
    void doesNotRouteToWrongTenant() {
        DataSource<?> ds = registry.register(new DataSourceDescriptor(
                Path.parse("siem"), "t1",
                new ClassObjectType<>(CloudEvent.class), null,
                Set.of(), Map.of()));
        List<Object> received = new ArrayList<>();
        ds.subscribe(received::add);

        router.onStartup(null);
        router.onCloudEvent(cloudEvent("siem.alert", "t2"));

        assertThat(received).isEmpty();
    }

    @Test
    void routesToPlatformGlobal() {
        DataSource<?> ds = registry.register(new DataSourceDescriptor(
                Path.parse("global"), io.casehub.platform.api.identity.TenancyConstants.PLATFORM_TENANT_ID,
                new ClassObjectType<>(CloudEvent.class), null,
                Set.of(), Map.of()));
        List<Object> received = new ArrayList<>();
        ds.subscribe(received::add);

        router.onStartup(null);
        router.onCloudEvent(cloudEvent("any.type", "any-tenant"));

        assertThat(received).hasSize(1);
    }

    @Test
    void acceptedEventTypes_filtersBeforeRouting() {
        DataSource<?> ds = registry.register(new DataSourceDescriptor(
                Path.parse("siem"), "t1",
                new ClassObjectType<>(CloudEvent.class), null,
                Set.of("siem.alert.critical"),
                Map.of()));
        List<Object> received = new ArrayList<>();
        ds.subscribe(received::add);

        router.onStartup(null);
        router.onCloudEvent(cloudEvent("siem.alert.info", "t1"));
        router.onCloudEvent(cloudEvent("siem.alert.critical", "t1"));

        assertThat(received).hasSize(1);
    }
}
```

- [ ] **Step 2: Implement DataSourceRouter**

```java
package io.casehub.platform.datasource;

import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceDescriptor;
import io.casehub.platform.api.datasource.DataSourceQuery;
import io.casehub.platform.api.datasource.DataSourceRegistered;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class DataSourceRouter {
    // Implementation: discover-at-startup + idempotent post-startup handler
    // Routes CloudEvents to DataSources by tenancy + acceptedEventTypes pre-filter
    // See spec §3 CDI Bridge for full routing logic
}
```

The router maintains an internal list of `(DataSourceDescriptor, DataSource<?>)` pairs, built at startup from `registry.discover()` and extended via `@ObservesAsync DataSourceRegistered`. On each `@ObservesAsync CloudEvent`:

1. Extract `tenancyid` extension
2. For each wired DataSource: check tenancy match (tenant-specific OR platform-global)
3. Check `acceptedEventTypes` pre-filter
4. Call `dataSource.add(cloudEvent)`

- [ ] **Step 3: Run full build**

Run: `mvn --batch-mode install`

- [ ] **Step 4: Commit**

```bash
git add platform/src/main/java/io/casehub/platform/datasource/DataSourceRouter.java datasource-inmem/src/test/java/io/casehub/platform/datasource/memory/DataSourceRouterTest.java
git commit -m "feat(platform#NNN): DataSourceRouter CDI bridge — routes CloudEvents to DataSources"
```

---

### Task 5: Update CLAUDE.md + ARC42STORIES.MD

**Files:**
- Modify: `CLAUDE.md` — add `datasource-inmem/` module entry, update package structure
- Modify: `ARC42STORIES.MD` — add DataSource layer entry (if applicable)

**Interfaces:**
- Consumes: All work from Tasks 1-4
- Produces: Documentation aligned with new module

- [ ] **Step 1: Add datasource-inmem to CLAUDE.md modules table**

Add entry following the pattern of existing modules:

```
| `datasource-inmem/` | `casehub-platform-datasource-inmem` | @Alternative @Priority(100) volatile InMemoryDataSourceRegistry — ConcurrentHashMap, alpha network (TypeNode, FilterNode, FanOutProcessor). No Flyway, no quarkus:build goal. Do NOT combine with datasource-drools in same scope |
```

- [ ] **Step 2: Add datasource package to platform-api package structure**

Add to the Package Structure section:

```
  .datasource    — DataSource (SPI: add-only event boundary + subscribe), DataSourceRegistry (register/resolve/discover),
                   ObjectType (pluggable type discriminator), ClassObjectType, DataProcessor, SubscriptionHandle,
                   FilterExpression (shareable predicate), DataSourceDescriptor, DataSourceQuery,
                   DataSourceRegistered (CDI event), Marshaller, MarshalException
```

- [ ] **Step 3: Update DataSourceRouter entry in platform/ module description**

Add to the `platform/` module entry: `DataSourceRouter @ApplicationScoped — bridges CDI CloudEvents into registered DataSources`

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md ARC42STORIES.MD
git commit -m "docs(platform#NNN): add datasource-inmem module and DataSource SPI to CLAUDE.md"
```
