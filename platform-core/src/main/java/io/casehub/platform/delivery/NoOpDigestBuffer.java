package io.casehub.platform.delivery;

import io.casehub.platform.api.delivery.DigestBuffer;
import io.casehub.platform.api.delivery.DigestBufferKey;
import io.casehub.platform.api.notification.NotificationInput;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class NoOpDigestBuffer implements DigestBuffer {
    @Override public void add(DigestBufferKey key, NotificationInput notification) {}
    @Override public List<NotificationInput> drain(DigestBufferKey key) { return List.of(); }
    @Override public Set<DigestBufferKey> pendingKeys() { return Set.of(); }
    @Override public Optional<Instant> oldestPendingTimestamp(DigestBufferKey key) { return Optional.empty(); }
    @Override public int pendingCount(DigestBufferKey key) { return 0; }
    @Override public Set<DigestBufferKey> pendingKeysForUser(String userId, String tenancyId) { return Set.of(); }
}
