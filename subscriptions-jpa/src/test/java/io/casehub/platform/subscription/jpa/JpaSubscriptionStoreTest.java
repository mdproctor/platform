package io.casehub.platform.subscription.jpa;

import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.subscription.NotificationTarget;
import io.casehub.platform.api.subscription.NotificationTemplate;
import io.casehub.platform.api.subscription.Subscription;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionQuery;
import io.casehub.platform.api.subscription.SubscriptionStore;
import io.casehub.platform.api.subscription.SubscriptionStoreContractTest;
import io.casehub.platform.api.subscription.SubscriptionUpdate;
import io.casehub.platform.api.subscription.TargetType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
public class JpaSubscriptionStoreTest extends SubscriptionStoreContractTest {

    @Inject
    SubscriptionStore blockingStore;

    @Inject
    EntityManager em;

    @Override
    protected SubscriptionStore store() {
        return blockingStore;
    }

    @Override
    @Transactional
    protected void clearState() {
        em.createQuery("DELETE FROM SubscriptionEntity").executeUpdate();
    }

    // Entity mapping verification (blocking store)

    @Test
    void entity_preservesFilters() {
        var filters = List.of(
                (ExpressionEvaluator) new MvelExpressionEvaluator("subject == 'case-123'"),
                (ExpressionEvaluator) new MvelExpressionEvaluator("source.startsWith('/tenants/')"));
        var input = new SubscriptionInput(
                "user-1", "tenant-1", "With Filters", "event-type",
                filters, List.of(new NotificationTarget(TargetType.USER, "user-1")),
                false, createTemplate(), true, null);

        var subscription = blockingStore.store(input);

        assertThat(subscription.filters()).hasSize(2);
        assertThat(subscription.filters().get(0).type()).isEqualTo("mvel");
        assertThat(subscription.filters().get(1).type()).isEqualTo("mvel");
    }

    @Test
    void entity_preservesTemplate() {
        var template = new NotificationTemplate(
                "Work item {entityId} created",
                "Actor {actorId} created work item {entityId}",
                NotificationSeverity.WARNING,
                "work-item.created",
                "/cases/{caseId}/work-items/{entityId}",
                "work-item",
                "entityId",
                "actorId"
        );
        var input = new SubscriptionInput("user-1", "tenant-1", "Template Sub", "event-type", List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")), false, template, true, null);

        var subscription = blockingStore.store(input);

        assertThat(subscription.template().titlePattern()).isEqualTo("Work item {entityId} created");
        assertThat(subscription.template().bodyPattern()).isEqualTo("Actor {actorId} created work item {entityId}");
        assertThat(subscription.template().severity()).isEqualTo(NotificationSeverity.WARNING);
        assertThat(subscription.template().category()).isEqualTo("work-item.created");
        assertThat(subscription.template().actionUrlPattern()).isEqualTo("/cases/{caseId}/work-items/{entityId}");
        assertThat(subscription.template().entityType()).isEqualTo("work-item");
        assertThat(subscription.template().entityIdField()).isEqualTo("entityId");
        assertThat(subscription.template().actorIdField()).isEqualTo("actorId");
    }

    @Test
    void entity_handlesEmptyConstraints() {
        var input = createTestInput("user-1", "tenant-1", "No Constraints", "event-type");

        var subscription = blockingStore.store(input);

        assertThat(subscription.filters()).isEmpty();
    }

    @Test
    void entity_roundTripsFiltersOnUpdate() {
        var subscription = blockingStore.store(
                createTestInput("user-1", "tenant-1", "Name", "event-type"));

        var newFilters = List.of(
                (ExpressionEvaluator) new MvelExpressionEvaluator("newField != 'excluded'"));
        var update  = new SubscriptionUpdate(null, null, newFilters, null, null, null, null);
        var updated = blockingStore.update(subscription.id(), "user-1", "tenant-1", update);

        assertThat(updated).isPresent();
        assertThat(updated.get().filters()).hasSize(1);
        assertThat(updated.get().filters().get(0).type()).isEqualTo("mvel");
    }

    // Cursor pagination verification

    @Test
    void cursor_paginationCoversAllResults() {
        for (int i = 0; i < 5; i++) {
            blockingStore.store(createTestInput("user-1", "tenant-1", "S" + i, "event-type"));
        }

        var    allIds = new java.util.ArrayList<String>();
        String cursor = null;
        int    pages  = 0;
        do {
            var query = new SubscriptionQuery("user-1", "tenant-1", null, null, cursor, 2);
            var page  = blockingStore.find(query);
            for (Subscription s : page.subscriptions()) {
                allIds.add(s.id());
            }
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(allIds).hasSize(5);
        assertThat(pages).isEqualTo(3);
        assertThat(allIds).doesNotHaveDuplicates();
    }

    // Helper

    private SubscriptionInput createTestInput(String ownerId, String tenancyId, String name, String eventType) {
        return new SubscriptionInput(ownerId, tenancyId, name, eventType,
                                     List.of(), List.of(new NotificationTarget(TargetType.USER, ownerId)), false,
                                     createTemplate(), true, null);
    }

    private NotificationTemplate createTemplate() {
        return new NotificationTemplate(
                "Title",
                null,
                NotificationSeverity.INFO,
                "category",
                null,
                "entity",
                "id",
                "actor"
        );
    }
}
