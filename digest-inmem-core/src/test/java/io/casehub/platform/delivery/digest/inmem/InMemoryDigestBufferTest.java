package io.casehub.platform.delivery.digest.inmem;

import io.casehub.platform.api.delivery.DigestBufferKey;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.preferences.IntPreference;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.PlatformPreferenceKeys;
import io.casehub.platform.api.preferences.PreferenceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDigestBufferTest {

    private InMemoryDigestBuffer buffer;
    private static final DigestBufferKey KEY = new DigestBufferKey("user-1", "tenant-1", "email");

    @BeforeEach
    void setUp() {
        buffer = new InMemoryDigestBuffer(500,
                retentionProvider(90));
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
        buffer = new InMemoryDigestBuffer(3,
                retentionProvider(90));
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

    // --- TTL expiry tests ---

    @Test
    void expired_entries_purged_from_pendingKeys() throws InterruptedException {
        var ttlBuffer = new InMemoryDigestBuffer(500, 50L);
        ttlBuffer.add(KEY, sampleInput("expiring"));
        Thread.sleep(100);
        assertThat(ttlBuffer.pendingKeys()).isEmpty();
    }

    @Test
    void drain_returnsEmpty_forExpiredBuffer() throws InterruptedException {
        var ttlBuffer = new InMemoryDigestBuffer(500, 50L);
        ttlBuffer.add(KEY, sampleInput("expiring"));
        Thread.sleep(100);
        assertThat(ttlBuffer.drain(KEY)).isEmpty();
    }

    @Test
    void pendingCount_returnsZero_forExpiredBuffer() throws InterruptedException {
        var ttlBuffer = new InMemoryDigestBuffer(500, 50L);
        ttlBuffer.add(KEY, sampleInput("expiring"));
        Thread.sleep(100);
        assertThat(ttlBuffer.pendingCount(KEY)).isEqualTo(0);
    }

    @Test
    void oldestPendingTimestamp_returnsEmpty_forExpiredBuffer() throws InterruptedException {
        var ttlBuffer = new InMemoryDigestBuffer(500, 50L);
        ttlBuffer.add(KEY, sampleInput("expiring"));
        Thread.sleep(100);
        assertThat(ttlBuffer.oldestPendingTimestamp(KEY)).isEmpty();
    }

    private static PreferenceProvider retentionProvider(int days) {
        return scope -> new MapPreferences(Map.of(
                PlatformPreferenceKeys.DIGEST_RETENTION_DAYS.qualifiedName(), IntPreference.of(days)));
    }

    private static NotificationInput sampleInput(String title) {
        return new NotificationInput("user-1", "tenant-1", title, null, "test",
                NotificationSeverity.INFO, null,
                new NotificationSource(UUID.randomUUID().toString(), "work-item", "wi-1", "actor-1"));
    }
}
