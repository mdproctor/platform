package io.casehub.platform.subscription.inmem;

import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.util.UUIDv7;
import io.casehub.platform.api.subscription.NotificationTarget;
import io.casehub.platform.api.subscription.NotificationTemplate;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionStore;
import io.casehub.platform.api.subscription.SubscriptionStoreContractTest;
import io.casehub.platform.api.subscription.TargetType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySubscriptionStoreTest extends SubscriptionStoreContractTest {

    private InMemorySubscriptionStore store;

    @Override
    protected SubscriptionStore store() {
        return store;
    }

    @Override
    protected void clearState() {
        store = new InMemorySubscriptionStore();
        UUIDv7.resetState(); // Reset thread-local UUID sequence state
    }

    // Additional tests for targets + includeActor

    @Test
    void store_withTargets_persistsTargets() {
        var targets = List.of(
                new NotificationTarget(TargetType.USER, "user-1"),
                new NotificationTarget(TargetType.GROUP, "managers")
        );
        var input = new SubscriptionInput(
                "owner-1",
                "tenant-1",
                "Multi-target Subscription",
                "work-item.created",
                List.of(),
                targets,
                false,
                createTemplate(),
                true,
                null
        );

        var subscription = store.store(input);

        assertThat(subscription.targets()).hasSize(2);
        assertThat(subscription.targets().get(0).type()).isEqualTo(TargetType.USER);
        assertThat(subscription.targets().get(0).id()).isEqualTo("user-1");
        assertThat(subscription.targets().get(1).type()).isEqualTo(TargetType.GROUP);
        assertThat(subscription.targets().get(1).id()).isEqualTo("managers");
    }

    @Test
    void store_withIncludeActor_persistsFlag() {
        var targets = List.of(new NotificationTarget(TargetType.USER, "user-1"));
        var input = new SubscriptionInput(
                "owner-1",
                "tenant-1",
                "Include Actor Subscription",
                "work-item.created",
                List.of(),
                targets,
                true,  // includeActor = true
                createTemplate(),
                true,
                null
        );

        var subscription = store.store(input);

        assertThat(subscription.includeActor()).isTrue();
    }

    @Test
    void findAllEnabled_returnsTargetsAndIncludeActor() {
        var targets = List.of(
                new NotificationTarget(TargetType.USER, "user-1"),
                new NotificationTarget(TargetType.EVENT_FIELD, "assigneeId")
        );
        var input = new SubscriptionInput(
                "owner-1",
                "tenant-1",
                "Enabled Subscription",
                "work-item.created",
                List.of(),
                targets,
                true,
                createTemplate(),
                true,
                null
        );

        store.store(input);

        try (var stream = store.findAllEnabled()) {
            var subscriptions = stream.toList();
            assertThat(subscriptions).hasSize(1);
            assertThat(subscriptions.get(0).targets()).hasSize(2);
            assertThat(subscriptions.get(0).includeActor()).isTrue();
        }
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
