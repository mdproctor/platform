package io.casehub.platform.notification.jpa;

import io.casehub.platform.api.notification.Notification;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationQuery;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.notification.NotificationStore;
import io.casehub.platform.api.notification.NotificationStoreContractTest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
public class JpaNotificationStoreTest extends NotificationStoreContractTest {

    @Inject
    NotificationStore blockingStore;

    @Inject
    EntityManager em;

    @Override
    protected NotificationStore store() {
        return blockingStore;
    }

    @Override
    @Transactional
    protected void clearState() {
        em.createQuery("DELETE FROM NotificationEntity").executeUpdate();
    }

    // Entity mapping verification

    @Test
    void entity_preservesAllSourceFields() {
        var source = new NotificationSource("evt-99", "case", "case-42", "actor-7");
        var input = new NotificationInput("user-1", "tenant-1", "Title", "Body",
                                          "case.updated", NotificationSeverity.WARNING, "/cases/42", source);

        var notification = blockingStore.store(input);

        assertThat(notification.source().eventId()).isEqualTo("evt-99");
        assertThat(notification.source().entityType()).isEqualTo("case");
        assertThat(notification.source().entityId()).isEqualTo("case-42");
        assertThat(notification.source().actorId()).isEqualTo("actor-7");
        assertThat(notification.severity()).isEqualTo(NotificationSeverity.WARNING);
        assertThat(notification.actionUrl()).isEqualTo("/cases/42");
    }

    @Test
    void entity_handlesNullableFields() {
        var source = new NotificationSource("evt-1", "type", "id-1", "actor-1");
        var input = new NotificationInput("user-1", "tenant-1", "Title", null,
                                          "category", NotificationSeverity.INFO, null, source);

        var notification = blockingStore.store(input);

        assertThat(notification.body()).isNull();
        assertThat(notification.actionUrl()).isNull();
    }

    // Cursor pagination verification

    @Test
    void cursor_paginationCoversAllResults() {
        for (int i = 0; i < 5; i++) {
            blockingStore.store(createTestInput("user-1", "tenant-1", "N" + i, "category"));
        }

        var    allIds = new java.util.ArrayList<String>();
        String cursor = null;
        int    pages  = 0;
        do {
            var query = new NotificationQuery("user-1", "tenant-1", null, null, cursor, 2);
            var page  = blockingStore.find(query);
            for (Notification n : page.notifications()) {
                allIds.add(n.id());
            }
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(allIds).hasSize(5);
        assertThat(pages).isEqualTo(3);
        assertThat(allIds).doesNotHaveDuplicates();
    }

    // Helper

    private NotificationInput createTestInput(String userId, String tenancyId, String title, String category) {
        var source = new NotificationSource("evt-" + System.nanoTime(), "work-item", "wi-456", "actor-789");
        return new NotificationInput(userId, tenancyId, title, "Body for " + title,
                                     category, NotificationSeverity.INFO, "/action", source);
    }
}
