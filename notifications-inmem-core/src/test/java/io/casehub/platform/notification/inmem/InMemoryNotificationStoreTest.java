package io.casehub.platform.notification.inmem;

import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.notification.NotificationStore;
import io.casehub.platform.api.notification.NotificationStoreContractTest;
import io.casehub.platform.api.util.UUIDv7;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryNotificationStoreTest extends NotificationStoreContractTest {

    private InMemoryNotificationStore store;

    @Override
    protected NotificationStore store() {
        return store;
    }

    @Override
    protected void clearState() {
        store = new InMemoryNotificationStore();
        UUIDv7.resetState(); // Reset thread-local UUID sequence state
    }

    // Eviction Tests

    @Test
    void store_evictsOldestWhenMaxSizeReached() {
        // Create store with max size of 3
        store = new InMemoryNotificationStore(3);

        var oldest = store.store(createInput("user-1", "tenant-1", "Oldest", "category"));
        var middle = store.store(createInput("user-1", "tenant-1", "Middle", "category"));
        var newer = store.store(createInput("user-1", "tenant-1", "Newer", "category"));
        var newest = store.store(createInput("user-1", "tenant-1", "Newest", "category"));

        // Oldest should be evicted
        assertThat(store.markRead(oldest.id(), "user-1", "tenant-1")).isEmpty();
        assertThat(store.markRead(middle.id(), "user-1", "tenant-1")).isPresent();
        assertThat(store.markRead(newer.id(), "user-1", "tenant-1")).isPresent();
        assertThat(store.markRead(newest.id(), "user-1", "tenant-1")).isPresent();
    }

    @Test
    void storeAll_evictsMultipleOldestWhenMaxSizeExceeded() {
        // Create store with max size of 3
        store = new InMemoryNotificationStore(3);

        var oldest = store.store(createInput("user-1", "tenant-1", "Oldest", "category"));
        var middle = store.store(createInput("user-1", "tenant-1", "Middle", "category"));

        // Store 3 more, should evict the first 2
        var input1 = createInput("user-1", "tenant-1", "New 1", "category");
        var input2 = createInput("user-1", "tenant-1", "New 2", "category");
        var input3 = createInput("user-1", "tenant-1", "New 3", "category");
        var newNotifications = store.storeAll(java.util.List.of(input1, input2, input3));

        // First two should be evicted
        assertThat(store.markRead(oldest.id(), "user-1", "tenant-1")).isEmpty();
        assertThat(store.markRead(middle.id(), "user-1", "tenant-1")).isEmpty();

        // New ones should remain
        assertThat(store.markRead(newNotifications.get(0).id(), "user-1", "tenant-1")).isPresent();
        assertThat(store.markRead(newNotifications.get(1).id(), "user-1", "tenant-1")).isPresent();
        assertThat(store.markRead(newNotifications.get(2).id(), "user-1", "tenant-1")).isPresent();
    }

    private NotificationInput createInput(String userId, String tenancyId, String title, String category) {
        var source = new NotificationSource("evt-123", "work-item", "wi-456", "actor-789");
        return new NotificationInput(
                userId,
                tenancyId,
                title,
                "Body for " + title,
                category,
                NotificationSeverity.INFO,
                "/action",
                source
        );
    }
}
