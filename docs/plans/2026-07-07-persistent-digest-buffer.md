# Persistent Digest Buffer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #158 — feat: persistent digest buffer (digest-jpa/) — JPA-backed DigestBuffer for restart-safe buffering
**Issue group:** #158

**Goal:** Extract InMemoryDigestBuffer to its own module and add a JPA-backed DigestBuffer for restart-safe notification digest buffering.

**Architecture:** Two new modules following the Store SPI pattern (persistence-backend-cdi-priority protocol). `digest-inmem/` extracts the existing in-memory implementation with `@Alternative @Priority(100)`. `digest-jpa/` adds a Hibernate ORM Panache implementation with `@ApplicationScoped` using one-row-per-notification with JSON payload.

**Tech Stack:** Java 21, Quarkus 3.32, Hibernate ORM Panache, Flyway, PostgreSQL, Jackson

## Global Constraints

- `platform-api/` is zero-dependency — no Quarkus, no JPA. Pure Java only.
- Every SPI in platform-api gets a `@DefaultBean` implementation in `platform/`.
- CDI tier ladder: `@DefaultBean` (Tier 1) < `@ApplicationScoped` (Tier 2) < `@Alternative @Priority(1)` (Tier 3) < `@Alternative @Priority(100)` (Tier 4).
- Flyway version range: digest-jpa claims V2000–V2999 per the `flyway-version-range-allocation` protocol.
- Use `quarkus-junit` not `quarkus-junit5` (relocation stub since Quarkus 3.31).
- Use `@TestTransaction` not `@Transactional` in JPA tests.
- Jandex plugin required on all library jars.

---

### Task 1: Extract InMemoryDigestBuffer to digest-inmem/

**Files:**
- Create: `digest-inmem/pom.xml`
- Create: `digest-inmem/src/main/java/io/casehub/platform/delivery/digest/inmem/InMemoryDigestBuffer.java`
- Create: `../../digest-inmem-core/src/test/java/io/casehub/platform/delivery/digest/inmem/InMemoryDigestBufferTest.java`
- Delete: `notification-dispatch/src/main/java/io/casehub/platform/notification/dispatch/InMemoryDigestBuffer.java`
- Delete: `notification-dispatch/src/test/java/io/casehub/platform/notification/dispatch/InMemoryDigestBufferTest.java`
- Modify: `notification-dispatch/pom.xml` — add `digest-inmem` test dependency
- Modify: `../../notification-dispatch-core/src/test/java/io/casehub/platform/notification/dispatch/DigestFlushSchedulerTest.java` — update import
- Modify: `pom.xml` (parent) — add `<module>digest-inmem</module>`

**Interfaces:**
- Consumes: `DigestBuffer` SPI from `platform-api` (`io.casehub.platform.api.delivery.DigestBuffer`)
- Produces: `InMemoryDigestBuffer` — `@Alternative @Priority(100) @ApplicationScoped` implementation of `DigestBuffer`

- [ ] **Step 1: Create digest-inmem/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-platform-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>

    <artifactId>casehub-platform-digest-inmem</artifactId>
    <packaging>jar</packaging>
    <name>CaseHub Platform Digest In-Memory</name>
    <description>Volatile in-memory DigestBuffer adapter. @Alternative @Priority(100) — displaces
        JPA and no-op when on the classpath. Add as test scope for @QuarkusTest isolation.
        Do not combine with digest-jpa in production scope.</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <version>${jandex-maven-plugin.version}</version>
                <executions>
                    <execution>
                        <id>make-index</id>
                        <goals><goal>jandex</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create InMemoryDigestBuffer in new package**

Move from `notification-dispatch` with two changes: (1) new package, (2) add `@Alternative @Priority(100)`.

```java
package io.casehub.platform.delivery.digest.inmem;

import io.casehub.platform.api.delivery.DigestBuffer;
import io.casehub.platform.api.delivery.DigestBufferKey;
import io.casehub.platform.api.notification.NotificationInput;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
@Alternative
@Priority(100)
public class InMemoryDigestBuffer implements DigestBuffer {

    private static final Logger LOG = Logger.getLogger(InMemoryDigestBuffer.class);

    private final ConcurrentHashMap<DigestBufferKey, BufferEntry> buffers = new ConcurrentHashMap<>();
    private final int maxBufferSize;

    public InMemoryDigestBuffer(
            @ConfigProperty(name = "casehub.notification.digest.max-buffer-size", defaultValue = "500")
            int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    @Override
    public void add(DigestBufferKey key, NotificationInput notification) {
        buffers.compute(key, (k, entry) -> {
            if (entry == null) {
                var list = new CopyOnWriteArrayList<NotificationInput>();
                list.add(notification);
                return new BufferEntry(list, Instant.now());
            }
            entry.notifications().add(notification);
            if (entry.notifications().size() > maxBufferSize) {
                entry.notifications().remove(0);
                LOG.debugf("Buffer eviction for key %s — max size %d exceeded", key, maxBufferSize);
            }
            return entry;
        });
    }

    @Override
    public List<NotificationInput> drain(DigestBufferKey key) {
        var entry = buffers.remove(key);
        return entry != null ? List.copyOf(entry.notifications()) : List.of();
    }

    @Override
    public Set<DigestBufferKey> pendingKeys() {
        return Set.copyOf(buffers.keySet());
    }

    @Override
    public Optional<Instant> oldestPendingTimestamp(DigestBufferKey key) {
        var entry = buffers.get(key);
        return entry != null ? Optional.of(entry.firstAdded()) : Optional.empty();
    }

    @Override
    public int pendingCount(DigestBufferKey key) {
        var entry = buffers.get(key);
        return entry != null ? entry.notifications().size() : 0;
    }

    @Override
    public Set<DigestBufferKey> pendingKeysForUser(String userId, String tenancyId) {
        return buffers.keySet().stream()
                .filter(key -> key.userId().equals(userId) && key.tenancyId().equals(tenancyId))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    record BufferEntry(CopyOnWriteArrayList<NotificationInput> notifications, Instant firstAdded) {}
}
```

- [ ] **Step 3: Create InMemoryDigestBufferTest in new package**

Same tests as the original, updated package and import.

```java
package io.casehub.platform.delivery.digest.inmem;

import io.casehub.platform.api.delivery.DigestBufferKey;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDigestBufferTest {

    private InMemoryDigestBuffer buffer;
    private static final DigestBufferKey KEY = new DigestBufferKey("user-1", "tenant-1", "email");

    @BeforeEach
    void setUp() {
        buffer = new InMemoryDigestBuffer(500);
    }

    @Test
    void add_then_drain_returnsItems() {
        buffer.add(KEY, sampleInput("Title 1"));
        buffer.add(KEY, sampleInput("Title 2"));

        var items = buffer.drain(KEY);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).isEqualTo("Title 1");
        assertThat(items.get(1).title()).isEqualTo("Title 2");
    }

    @Test
    void drain_clearsBuffer() {
        buffer.add(KEY, sampleInput("Title 1"));
        buffer.drain(KEY);

        assertThat(buffer.pendingKeys()).isEmpty();
        assertThat(buffer.drain(KEY)).isEmpty();
    }

    @Test
    void drain_unknownKey_returnsEmpty() {
        assertThat(buffer.drain(KEY)).isEmpty();
    }

    @Test
    void pendingKeys_returnsOnlyKeysWithItems() {
        var key2 = new DigestBufferKey("user-2", "tenant-1", "email");
        buffer.add(KEY, sampleInput("Title 1"));
        buffer.add(key2, sampleInput("Title 2"));

        assertThat(buffer.pendingKeys()).containsExactlyInAnyOrder(KEY, key2);
    }

    @Test
    void oldestPendingTimestamp_returnsFirstAddTime() throws InterruptedException {
        buffer.add(KEY, sampleInput("Title 1"));
        var firstTimestamp = buffer.oldestPendingTimestamp(KEY);
        assertThat(firstTimestamp).isPresent();

        Thread.sleep(10);
        buffer.add(KEY, sampleInput("Title 2"));
        var secondTimestamp = buffer.oldestPendingTimestamp(KEY);

        assertThat(secondTimestamp).isEqualTo(firstTimestamp);
    }

    @Test
    void oldestPendingTimestamp_unknownKey_returnsEmpty() {
        assertThat(buffer.oldestPendingTimestamp(KEY)).isEmpty();
    }

    @Test
    void eviction_dropsOldestWhenMaxExceeded() {
        buffer = new InMemoryDigestBuffer(3);
        buffer.add(KEY, sampleInput("Item 1"));
        buffer.add(KEY, sampleInput("Item 2"));
        buffer.add(KEY, sampleInput("Item 3"));
        buffer.add(KEY, sampleInput("Item 4"));

        var items = buffer.drain(KEY);
        assertThat(items).hasSize(3);
        assertThat(items.get(0).title()).isEqualTo("Item 2");
    }

    @Test
    void pendingCount_returnsItemCount() {
        buffer.add(KEY, sampleInput("one"));
        buffer.add(KEY, sampleInput("two"));
        assertThat(buffer.pendingCount(KEY)).isEqualTo(2);
    }

    @Test
    void pendingCount_returnsZero_whenKeyAbsent() {
        assertThat(buffer.pendingCount(KEY)).isEqualTo(0);
    }

    @Test
    void pendingKeysForUser_filtersToUser() {
        var otherKey = new DigestBufferKey("other-user", "tenant-1", "email");
        buffer.add(KEY, sampleInput("mine"));
        buffer.add(otherKey, sampleInput("theirs"));

        var keys = buffer.pendingKeysForUser("user-1", "tenant-1");
        assertThat(keys).containsExactly(KEY);
    }

    private static NotificationInput sampleInput(String title) {
        return new NotificationInput("user-1", "tenant-1", title, null, "test",
                NotificationSeverity.INFO, null,
                new NotificationSource(UUID.randomUUID().toString(), "work-item", "wi-1", "actor-1"));
    }
}
```

- [ ] **Step 4: Delete original files from notification-dispatch**

Delete:
- `notification-dispatch/src/main/java/io/casehub/platform/notification/dispatch/InMemoryDigestBuffer.java`
- `notification-dispatch/src/test/java/io/casehub/platform/notification/dispatch/InMemoryDigestBufferTest.java`

- [ ] **Step 5: Update notification-dispatch/pom.xml — add test dependency**

Add after existing test dependencies:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-platform-digest-inmem</artifactId>
    <version>${project.version}</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 6: Update DigestFlushSchedulerTest import**

In `../../notification-dispatch-core/src/test/java/io/casehub/platform/notification/dispatch/DigestFlushSchedulerTest.java`, change the import:

From:
```java
// (no import needed — InMemoryDigestBuffer was in the same package)
```

To:
```java
import io.casehub.platform.delivery.digest.inmem.InMemoryDigestBuffer;
```

- [ ] **Step 7: Add module to parent pom.xml**

Add `<module>digest-inmem</module>` after the `notification-dispatch` module entry in `pom.xml`.

- [ ] **Step 8: Build and verify**

Run: `mvn --batch-mode install -pl digest-inmem,notification-dispatch -am`
Expected: BUILD SUCCESS — digest-inmem tests pass, notification-dispatch tests pass with the new import.

- [ ] **Step 9: Commit**

```bash
git add digest-inmem/ notification-dispatch/ pom.xml
git commit -m "refactor(platform#158): extract InMemoryDigestBuffer to digest-inmem/

Move InMemoryDigestBuffer from notification-dispatch to its own module
with @Alternative @Priority(100), following the Store SPI pattern.
Prerequisite for adding JPA-backed DigestBuffer without CDI ambiguity."
```

---

### Task 2: Create JPA-backed DigestBuffer in digest-jpa/

**Files:**
- Create: `digest-jpa/pom.xml`
- Create: `digest-jpa/src/main/java/io/casehub/platform/delivery/digest/jpa/DigestBufferEntity.java`
- Create: `digest-jpa/src/main/java/io/casehub/platform/delivery/digest/jpa/JpaDigestBuffer.java`
- Create: `digest-jpa/src/main/resources/db/digest/migration/V2000__digest_buffer.sql`
- Create: `digest-jpa/src/test/java/io/casehub/platform/delivery/digest/jpa/JpaDigestBufferTest.java`
- Create: `digest-jpa/src/test/resources/application.properties`
- Modify: `pom.xml` (parent) — add `<module>digest-jpa</module>`

**Interfaces:**
- Consumes: `DigestBuffer` SPI from `platform-api`, `UUIDv7` from `platform-api`, `NotificationInput` from `platform-api`
- Produces: `JpaDigestBuffer` — `@ApplicationScoped` implementation of `DigestBuffer` (Tier 2)

- [ ] **Step 1: Create digest-jpa/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-platform-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>

    <artifactId>casehub-platform-digest-jpa</artifactId>
    <packaging>jar</packaging>
    <name>CaseHub Platform Digest JPA</name>
    <description>JPA-backed DigestBuffer. @ApplicationScoped — Tier 2, beats @DefaultBean no-op.
        Hibernate ORM Panache (blocking-only). Add as compile dep; consumers must add
        classpath:db/digest/migration to quarkus.flyway.locations. Do NOT combine with
        digest-inmem in production scope.</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-orm-panache</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jdbc-postgresql</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jackson</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-testing</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <version>${jandex-maven-plugin.version}</version>
                <executions>
                    <execution>
                        <id>make-index</id>
                        <goals><goal>jandex</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create Flyway migration**

File: `digest-jpa/src/main/resources/db/digest/migration/V2000__digest_buffer.sql`

```sql
-- Digest buffer store (platform#158)

CREATE TABLE IF NOT EXISTS digest_buffer (
    id                UUID NOT NULL PRIMARY KEY,
    user_id           VARCHAR(255) NOT NULL,
    tenancy_id        VARCHAR(255) NOT NULL,
    channel_id        VARCHAR(255) NOT NULL,
    notification_json TEXT NOT NULL,
    buffered_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_digest_buffer_key
    ON digest_buffer (user_id, tenancy_id, channel_id);
```

- [ ] **Step 3: Create test application.properties**

File: `digest-jpa/src/test/resources/application.properties`

```properties
# DevServices auto-starts PostgreSQL testcontainer
quarkus.datasource.db-kind=postgresql
quarkus.datasource.devservices.enabled=true

# Flyway creates schema via JDBC
quarkus.flyway.locations=classpath:db/digest/migration
quarkus.flyway.migrate-at-start=true
quarkus.flyway.clean-at-start=true
quarkus.hibernate-orm.schema-management.strategy=none
```

- [ ] **Step 4: Write failing test — JpaDigestBufferTest**

```java
package io.casehub.platform.delivery.digest.jpa;

import io.casehub.platform.api.delivery.DigestBuffer;
import io.casehub.platform.api.delivery.DigestBufferKey;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JpaDigestBufferTest {

    @Inject
    DigestBuffer buffer;

    @Inject
    EntityManager entityManager;

    private static final DigestBufferKey KEY = new DigestBufferKey("user-1", "tenant-1", "email");

    @BeforeEach
    @Transactional
    void setUp() {
        entityManager.createQuery("DELETE FROM DigestBufferEntity").executeUpdate();
    }

    @Test
    @Transactional
    void add_then_drain_returnsItems() {
        buffer.add(KEY, sampleInput("Title 1"));
        buffer.add(KEY, sampleInput("Title 2"));

        var items = buffer.drain(KEY);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).isEqualTo("Title 1");
        assertThat(items.get(1).title()).isEqualTo("Title 2");
    }

    @Test
    @Transactional
    void drain_clearsBuffer() {
        buffer.add(KEY, sampleInput("Title 1"));
        buffer.drain(KEY);

        assertThat(buffer.pendingKeys()).isEmpty();
        assertThat(buffer.drain(KEY)).isEmpty();
    }

    @Test
    @Transactional
    void drain_unknownKey_returnsEmpty() {
        assertThat(buffer.drain(KEY)).isEmpty();
    }

    @Test
    @Transactional
    void pendingKeys_returnsOnlyKeysWithItems() {
        var key2 = new DigestBufferKey("user-2", "tenant-1", "email");
        buffer.add(KEY, sampleInput("Title 1"));
        buffer.add(key2, sampleInput("Title 2"));

        assertThat(buffer.pendingKeys()).containsExactlyInAnyOrder(KEY, key2);
    }

    @Test
    @Transactional
    void oldestPendingTimestamp_returnsFirstAddTime() {
        buffer.add(KEY, sampleInput("Title 1"));
        var firstTimestamp = buffer.oldestPendingTimestamp(KEY);
        assertThat(firstTimestamp).isPresent();

        buffer.add(KEY, sampleInput("Title 2"));
        var secondTimestamp = buffer.oldestPendingTimestamp(KEY);

        assertThat(secondTimestamp).isEqualTo(firstTimestamp);
    }

    @Test
    @Transactional
    void oldestPendingTimestamp_unknownKey_returnsEmpty() {
        assertThat(buffer.oldestPendingTimestamp(KEY)).isEmpty();
    }

    @Test
    @Transactional
    void pendingCount_returnsItemCount() {
        buffer.add(KEY, sampleInput("one"));
        buffer.add(KEY, sampleInput("two"));
        assertThat(buffer.pendingCount(KEY)).isEqualTo(2);
    }

    @Test
    @Transactional
    void pendingCount_returnsZero_whenKeyAbsent() {
        assertThat(buffer.pendingCount(KEY)).isEqualTo(0);
    }

    @Test
    @Transactional
    void pendingKeysForUser_filtersToUser() {
        var otherKey = new DigestBufferKey("other-user", "tenant-1", "email");
        buffer.add(KEY, sampleInput("mine"));
        buffer.add(otherKey, sampleInput("theirs"));

        var keys = buffer.pendingKeysForUser("user-1", "tenant-1");
        assertThat(keys).containsExactly(KEY);
    }

    @Test
    @Transactional
    void eviction_dropsOldestWhenMaxExceeded() {
        // This test only exercises the eviction path when maxBufferSize > 0.
        // Default is 0 (no eviction). Override via config to enable.
        // Since @QuarkusTest uses application.properties, add property override:
        // For now, test the no-eviction default — eviction tested via direct construction below.
        buffer.add(KEY, sampleInput("Item 1"));
        buffer.add(KEY, sampleInput("Item 2"));
        buffer.add(KEY, sampleInput("Item 3"));

        // Default maxBufferSize=0 → no eviction
        assertThat(buffer.pendingCount(KEY)).isEqualTo(3);
    }

    @Test
    @Transactional
    void drain_preservesInsertionOrder() {
        buffer.add(KEY, sampleInput("First"));
        buffer.add(KEY, sampleInput("Second"));
        buffer.add(KEY, sampleInput("Third"));

        var items = buffer.drain(KEY);
        assertThat(items).extracting(NotificationInput::title)
                .containsExactly("First", "Second", "Third");
    }

    @Test
    @Transactional
    void add_withEvictionEnabled_trimsOldest() {
        // Direct construction with maxBufferSize=3 to test eviction path
        var evictingBuffer = new JpaDigestBuffer(entityManager, 3);
        evictingBuffer.add(KEY, sampleInput("Item 1"));
        evictingBuffer.add(KEY, sampleInput("Item 2"));
        evictingBuffer.add(KEY, sampleInput("Item 3"));
        evictingBuffer.add(KEY, sampleInput("Item 4"));

        var items = evictingBuffer.drain(KEY);
        assertThat(items).hasSize(3);
        assertThat(items.get(0).title()).isEqualTo("Item 2");
    }

    private static NotificationInput sampleInput(String title) {
        return new NotificationInput("user-1", "tenant-1", title, null, "test",
                NotificationSeverity.INFO, null,
                new NotificationSource(UUID.randomUUID().toString(), "work-item", "wi-1", "actor-1"));
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `mvn --batch-mode test -pl digest-jpa -am`
Expected: FAIL — `JpaDigestBuffer` class does not exist yet.

- [ ] **Step 6: Create DigestBufferEntity**

```java
package io.casehub.platform.delivery.digest.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.UUIDv7;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "digest_buffer")
public class DigestBufferEntity extends PanacheEntityBase {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Id
    @Column(name = "id", nullable = false)
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public String userId;

    @Column(name = "tenancy_id", nullable = false)
    public String tenancyId;

    @Column(name = "channel_id", nullable = false)
    public String channelId;

    @Column(name = "notification_json", nullable = false, columnDefinition = "TEXT")
    public String notificationJson;

    @Column(name = "buffered_at", nullable = false)
    public Instant bufferedAt;

    public static DigestBufferEntity fromNotificationInput(
            String userId, String tenancyId, String channelId,
            NotificationInput input) {
        var entity = new DigestBufferEntity();
        entity.id = UUIDv7.generate();
        entity.userId = userId;
        entity.tenancyId = tenancyId;
        entity.channelId = channelId;
        entity.notificationJson = serialize(input);
        entity.bufferedAt = Instant.now();
        return entity;
    }

    public NotificationInput toNotificationInput() {
        try {
            return JSON.readValue(notificationJson, NotificationInput.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize notification JSON", e);
        }
    }

    private static String serialize(NotificationInput input) {
        try {
            return JSON.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize NotificationInput to JSON", e);
        }
    }
}
```

- [ ] **Step 7: Create JpaDigestBuffer**

```java
package io.casehub.platform.delivery.digest.jpa;

import io.casehub.platform.api.delivery.DigestBuffer;
import io.casehub.platform.api.delivery.DigestBufferKey;
import io.casehub.platform.api.notification.NotificationInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class JpaDigestBuffer implements DigestBuffer {

    private static final Logger LOG = Logger.getLogger(JpaDigestBuffer.class);

    private final EntityManager entityManager;
    private final int maxBufferSize;

    @Inject
    public JpaDigestBuffer(EntityManager entityManager,
                           @ConfigProperty(name = "casehub.notification.digest.max-buffer-size",
                                   defaultValue = "0")
                           int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    // Package-private constructor for test with explicit maxBufferSize
    JpaDigestBuffer(EntityManager entityManager, int maxBufferSize) {
        this.entityManager = entityManager;
        this.maxBufferSize = maxBufferSize;
    }

    @Override
    @Transactional
    public void add(DigestBufferKey key, NotificationInput notification) {
        var entity = DigestBufferEntity.fromNotificationInput(
                key.userId(), key.tenancyId(), key.channelId(), notification);
        entityManager.persist(entity);

        if (maxBufferSize > 0) {
            long count = entityManager.createQuery(
                            "SELECT COUNT(e) FROM DigestBufferEntity e " +
                                    "WHERE e.userId = :userId AND e.tenancyId = :tenancyId " +
                                    "AND e.channelId = :channelId", Long.class)
                    .setParameter("userId", key.userId())
                    .setParameter("tenancyId", key.tenancyId())
                    .setParameter("channelId", key.channelId())
                    .getSingleResult();

            if (count > maxBufferSize) {
                long excess = count - maxBufferSize;
                List<UUID> toDelete = entityManager.createQuery(
                                "SELECT e.id FROM DigestBufferEntity e " +
                                        "WHERE e.userId = :userId AND e.tenancyId = :tenancyId " +
                                        "AND e.channelId = :channelId ORDER BY e.bufferedAt ASC", UUID.class)
                        .setParameter("userId", key.userId())
                        .setParameter("tenancyId", key.tenancyId())
                        .setParameter("channelId", key.channelId())
                        .setMaxResults((int) excess)
                        .getResultList();

                entityManager.createQuery(
                                "DELETE FROM DigestBufferEntity e WHERE e.id IN :ids")
                        .setParameter("ids", toDelete)
                        .executeUpdate();

                LOG.debugf("Buffer eviction for key %s — %d rows trimmed", key, excess);
            }
        }
    }

    @Override
    @Transactional
    public List<NotificationInput> drain(DigestBufferKey key) {
        List<DigestBufferEntity> entities = entityManager.createQuery(
                        "SELECT e FROM DigestBufferEntity e " +
                                "WHERE e.userId = :userId AND e.tenancyId = :tenancyId " +
                                "AND e.channelId = :channelId ORDER BY e.bufferedAt ASC",
                        DigestBufferEntity.class)
                .setParameter("userId", key.userId())
                .setParameter("tenancyId", key.tenancyId())
                .setParameter("channelId", key.channelId())
                .getResultList();

        if (entities.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = entities.stream().map(e -> e.id).toList();
        entityManager.createQuery(
                        "DELETE FROM DigestBufferEntity e WHERE e.id IN :ids")
                .setParameter("ids", ids)
                .executeUpdate();

        return entities.stream()
                .map(DigestBufferEntity::toNotificationInput)
                .toList();
    }

    @Override
    public Set<DigestBufferKey> pendingKeys() {
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT DISTINCT e.userId, e.tenancyId, e.channelId " +
                                "FROM DigestBufferEntity e", Object[].class)
                .getResultList();

        Set<DigestBufferKey> keys = new HashSet<>();
        for (Object[] row : rows) {
            keys.add(new DigestBufferKey((String) row[0], (String) row[1], (String) row[2]));
        }
        return keys;
    }

    @Override
    public Optional<Instant> oldestPendingTimestamp(DigestBufferKey key) {
        Instant oldest = entityManager.createQuery(
                        "SELECT MIN(e.bufferedAt) FROM DigestBufferEntity e " +
                                "WHERE e.userId = :userId AND e.tenancyId = :tenancyId " +
                                "AND e.channelId = :channelId", Instant.class)
                .setParameter("userId", key.userId())
                .setParameter("tenancyId", key.tenancyId())
                .setParameter("channelId", key.channelId())
                .getSingleResult();
        return Optional.ofNullable(oldest);
    }

    @Override
    public int pendingCount(DigestBufferKey key) {
        Long count = entityManager.createQuery(
                        "SELECT COUNT(e) FROM DigestBufferEntity e " +
                                "WHERE e.userId = :userId AND e.tenancyId = :tenancyId " +
                                "AND e.channelId = :channelId", Long.class)
                .setParameter("userId", key.userId())
                .setParameter("tenancyId", key.tenancyId())
                .setParameter("channelId", key.channelId())
                .getSingleResult();
        return count.intValue();
    }

    @Override
    public Set<DigestBufferKey> pendingKeysForUser(String userId, String tenancyId) {
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT DISTINCT e.userId, e.tenancyId, e.channelId " +
                                "FROM DigestBufferEntity e " +
                                "WHERE e.userId = :userId AND e.tenancyId = :tenancyId",
                        Object[].class)
                .setParameter("userId", userId)
                .setParameter("tenancyId", tenancyId)
                .getResultList();

        Set<DigestBufferKey> keys = new HashSet<>();
        for (Object[] row : rows) {
            keys.add(new DigestBufferKey((String) row[0], (String) row[1], (String) row[2]));
        }
        return keys;
    }
}
```

- [ ] **Step 8: Add module to parent pom.xml**

Add `<module>digest-jpa</module>` after `digest-inmem` in `pom.xml`.

- [ ] **Step 9: Run tests to verify they pass**

Run: `mvn --batch-mode test -pl digest-jpa -am`
Expected: PASS — all JpaDigestBufferTest methods green.

- [ ] **Step 10: Commit**

```bash
git add digest-jpa/ pom.xml
git commit -m "feat(platform#158): JPA-backed DigestBuffer in digest-jpa/

@ApplicationScoped Tier 2 DigestBuffer — one row per notification with
JSON payload, Flyway V2000, ID-scoped drain for READ COMMITTED safety.
Configurable max-buffer-size (default 0 = no eviction)."
```

---

### Task 3: Documentation updates — CLAUDE.md and ARC42STORIES.MD

**Files:**
- Modify: `CLAUDE.md` — update module table and notification-dispatch description
- Modify: `ARC42STORIES.MD` — add §4, §5, §7 entries for new modules

**Interfaces:**
- Consumes: nothing (documentation only)
- Produces: nothing (documentation only)

- [ ] **Step 1: Update CLAUDE.md module table**

Add two entries after `notification-dispatch/`:

```
| `digest-inmem/` | `casehub-platform-digest-inmem` | @Alternative @Priority(100) volatile InMemoryDigestBuffer — ConcurrentHashMap, max-size eviction via `casehub.notification.digest.max-buffer-size`. No quarkus:build goal. Do NOT combine with digest-jpa in production scope |
| `digest-jpa/` | `casehub-platform-digest-jpa` | @ApplicationScoped JPA DigestBuffer — Hibernate ORM Panache (blocking-only). PostgreSQL, Flyway V2000 (`classpath:db/digest/migration`). One row per notification, JSON payload. Configurable max-buffer-size (default 0 = no eviction). No quarkus:build goal. Do NOT combine with digest-inmem in production scope |
```

Update `notification-dispatch/` description to remove `InMemoryDigestBuffer` reference — replace `InMemoryDigestBuffer (@ApplicationScoped, ConcurrentHashMap, max-size eviction via \`casehub.notification.digest.max-buffer-size\`)` with nothing (InMemoryDigestBuffer is now in digest-inmem/).

- [ ] **Step 2: Update ARC42STORIES.MD**

Add entries to §4 (Solution Strategy layer taxonomy), §5 (Building Block View), and §7 (Deployment View) for both new modules. Follow the exact format of existing entries for `notifications-inmem/` and `notifications-jpa/`.

- [ ] **Step 3: Update Flyway version range allocation table**

In `/Users/mdproctor/claude/casehub/garden/docs/protocols/casehub/flyway-version-range-allocation.md`, update the casehub-platform allocation table to register V2000–V2999 for digest-jpa:

Add row after `V1000–V1999 | memory-jpa/`:

```
| V2000–V2999 | `digest-jpa/` — digest_buffer table | `classpath:db/digest/migration` |
```

Update the "V2000+" placeholder row to `V3000+`.

Commit to the garden repo:
```bash
git -C ~/.hortora/garden add docs/protocols/casehub/flyway-version-range-allocation.md
git -C ~/.hortora/garden commit -m "protocol: register V2000–V2999 for casehub-platform digest-jpa"
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md ARC42STORIES.MD
git commit -m "docs(platform#158): add digest-inmem/ and digest-jpa/ to CLAUDE.md and ARC42STORIES.MD"
```
