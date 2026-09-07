package io.casehub.platform.subscription.engine;

import io.casehub.platform.api.datasource.ClassObjectType;
import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceDeregistered;
import io.casehub.platform.api.datasource.DataSourceDescriptor;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.subscription.NotificationTarget;
import io.casehub.platform.api.subscription.NotificationTemplate;
import io.casehub.platform.api.subscription.SubscribableEvent;
import io.casehub.platform.api.subscription.Subscription;
import io.casehub.platform.api.subscription.SubscriptionCreated;
import io.casehub.platform.api.subscription.SubscriptionDeleted;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionMatched;
import io.casehub.platform.api.subscription.SubscriptionScope;
import io.casehub.platform.api.subscription.SubscriptionUpdated;
import io.casehub.platform.api.subscription.TargetType;
import io.casehub.platform.datasource.alpha.AlphaDataSource;
import io.casehub.platform.datasource.memory.InMemoryDataSourceRegistry;
import io.casehub.platform.subscription.inmem.InMemorySubscriptionStore;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.casehub.platform.api.identity.TenancyConstants.PLATFORM_TENANT_ID;
import static io.casehub.platform.api.subscription.SubscriptionConstants.NOTIFICATION_DATASOURCE_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionEngineTest {

    private InMemoryDataSourceRegistry registry;
    private InMemorySubscriptionStore subStore;
    private Event<SubscriptionMatched> matchEvent;
    private SubscriptionEngine engine;
    private List<SubscriptionMatched> firedEvents;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        registry = new InMemoryDataSourceRegistry(null, null, null);
        subStore = new InMemorySubscriptionStore(null, null, null);
        matchEvent = mock(Event.class);
        firedEvents = Collections.synchronizedList(new ArrayList<>());

        // Mock fireAsync to capture events and return completed future
        when(matchEvent.fireAsync(any(SubscriptionMatched.class))).thenAnswer(invocation -> {
            SubscriptionMatched event = invocation.getArgument(0);
            firedEvents.add(event);
            return CompletableFuture.completedFuture(event);
        });

        var exprRegistry = new io.casehub.platform.expression.DefaultExpressionEngineRegistry(
                java.util.List.of(new io.casehub.platform.expression.MvelExpressionEngine()));
        engine = new SubscriptionEngine(registry, subStore, matchEvent, exprRegistry);
    }

    // --- Startup ---

    @Test
    void startup_registersNotificationDataSource() {
        engine.onStartup(null);

        var ds = registry.resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID);
        assertThat(ds).isPresent();
    }

    @Test
    void startup_wiresExistingEnabledSubscriptions() {
        subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", true));
        subStore.store(subscriptionInput("user-2", "tenant-1",
                "io.casehub.work.workitem.completed", true));

        engine.onStartup(null);

        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).hasSize(2);
        verify(matchEvent, times(2)).fireAsync(any(SubscriptionMatched.class));
    }

    @Test
    void startup_doesNotWireDisabledSubscriptions() {
        subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", false));

        engine.onStartup(null);

        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).isEmpty();
        verify(matchEvent, never()).fireAsync(any(SubscriptionMatched.class));
    }

    // --- Event Matching ---

    @Test
    void event_matchesSubscription_firesSubscriptionMatched() {
        var template = new NotificationTemplate("WorkItem {status}", null,
                NotificationSeverity.INFO, "work-item.completed",
                null, "work-item", "workItemId", "actor");
        var input = new SubscriptionInput("user-1", "tenant-1", "My sub",
                "io.casehub.work.workitem.completed",
                List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")),
                false, template, true, null);
        var storedSub = subStore.store(input);

        engine.onStartup(null);

        var eventId = UUID.randomUUID();
        pushEvent("io.casehub.work.workitem.completed", "tenant-1", eventId, "user-2");

        assertThat(firedEvents).hasSize(1);
        var matched = firedEvents.get(0);
        assertThat(matched.subscription().id()).isEqualTo(storedSub.id());
        assertThat(matched.subscription().ownerId()).isEqualTo("user-1");
        assertThat(((TestEvent) matched.pojo()).workItemId()).isEqualTo(eventId);
        assertThat(((TestEvent) matched.pojo()).status()).isEqualTo("completed");
    }

    @Test
    void event_wrongEventType_doesNotMatch() {
        subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", true));

        engine.onStartup(null);

        pushEvent("io.casehub.work.workitem.created", "tenant-1",
                UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).isEmpty();
        verify(matchEvent, never()).fireAsync(any(SubscriptionMatched.class));
    }

    // --- Tenant Isolation ---

    @Test
    void event_wrongTenant_doesNotMatch() {
        subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", true));

        engine.onStartup(null);

        pushEvent("io.casehub.work.workitem.completed", "tenant-2",
                UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).isEmpty();
        verify(matchEvent, never()).fireAsync(any(SubscriptionMatched.class));
    }

    @Test
    void event_matchesSameTenantOnly() {
        subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", true));
        subStore.store(subscriptionInput("user-2", "tenant-2",
                "io.casehub.work.workitem.completed", true));

        engine.onStartup(null);

        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).subscription().ownerId()).isEqualTo("user-1");
        assertThat(firedEvents.get(0).subscription().tenancyId()).isEqualTo("tenant-1");
    }

    // --- Dynamic Wiring: SubscriptionCreated ---

    @Test
    void onCreated_wiresEnabledSubscription() {
        engine.onStartup(null);

        var sub = subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", true));
        engine.onCreated(new SubscriptionCreated(sub));

        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).hasSize(1);
    }

    @Test
    void onCreated_doesNotWireDisabledSubscription() {
        engine.onStartup(null);

        var sub = subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", false));
        engine.onCreated(new SubscriptionCreated(sub));

        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).isEmpty();
    }

    // --- Dynamic Wiring: SubscriptionUpdated ---

    @Test
    void onUpdated_rewiresSubscription() {
        // Start with a subscription matching event type A
        subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", true));
        engine.onStartup(null);

        // Update to match event type B
        var allEnabled = subStore.findAllEnabled().toList();
        var original = allEnabled.get(0);
        var updated = new Subscription(
                original.id(), original.ownerId(), original.tenancyId(), original.name(),
                "io.casehub.work.workitem.created",
                original.filters(), original.targets(), original.includeActor(),
                original.template(), true,
                SubscriptionScope.USER, original.createdAt(), Instant.now());

        engine.onUpdated(new SubscriptionUpdated(updated, original));

        // Old type should no longer match
        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");
        assertThat(firedEvents).isEmpty();

        // New type should match
        pushEvent("io.casehub.work.workitem.created", "tenant-1",
                UUID.randomUUID(), "actor-1");
        assertThat(firedEvents).hasSize(1);
    }

    @Test
    void onUpdated_disablingUnwiresSubscription() {
        subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", true));
        engine.onStartup(null);

        var original = subStore.findAllEnabled().toList().get(0);
        var disabled = new Subscription(
                original.id(), original.ownerId(), original.tenancyId(), original.name(),
                original.eventType(), original.filters(), original.targets(),
                original.includeActor(), original.template(), false,
                SubscriptionScope.USER, original.createdAt(), Instant.now());

        engine.onUpdated(new SubscriptionUpdated(disabled, original));

        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).isEmpty();
    }

    // --- Dynamic Wiring: SubscriptionDeleted ---

    @Test
    void onDeleted_unwiresSubscription() {
        subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", true));
        engine.onStartup(null);

        // Verify it was wired
        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");
        assertThat(firedEvents).hasSize(1);

        // Delete and push again
        firedEvents.clear();
        var sub = subStore.findAllEnabled().toList().get(0);
        engine.onDeleted(new SubscriptionDeleted(sub));

        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).isEmpty();
    }

    @Test
    void onDeleted_noOpWhenSubscriptionNotWired() {
        engine.onStartup(null);

        // Delete a subscription that was never wired — should not throw
        var ghost = new Subscription("ghost-id", "user-1", "tenant-1", "Ghost",
                "io.casehub.work.ghost", List.of(),
                List.of(new NotificationTarget(TargetType.USER, "user-1")),
                false, defaultTemplate(), true,
                SubscriptionScope.USER, Instant.now(), Instant.now());
        engine.onDeleted(new SubscriptionDeleted(ghost));

        // Engine still operational
        pushEvent("io.casehub.work.ghost", "tenant-1", UUID.randomUUID(), "actor-1");
        assertThat(firedEvents).isEmpty();
    }

    // --- Ghost subscription prevention (R2-03) ---

    @Test
    void concurrentUpdates_noGhostSubscriptions() {
        subStore.store(subscriptionInput("user-1", "tenant-1",
                "io.casehub.work.workitem.completed", true));
        engine.onStartup(null);

        var original = subStore.findAllEnabled().toList().get(0);

        // Simulate rapid update + delete — delete must win
        var updated = new Subscription(
                original.id(), original.ownerId(), original.tenancyId(), original.name(),
                "io.casehub.work.workitem.created",
                original.filters(), original.targets(), original.includeActor(),
                original.template(), true,
                SubscriptionScope.USER, original.createdAt(), Instant.now());

        engine.onUpdated(new SubscriptionUpdated(updated, original));
        engine.onDeleted(new SubscriptionDeleted(updated));

        // Neither old nor new type should match
        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");
        pushEvent("io.casehub.work.workitem.created", "tenant-1",
                UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).isEmpty();
    }

    // --- Template resolution failure ---

    @Test
    void event_matchesSubscription_firesEventRegardlessOfTemplateValidity() {
        // Template with unresolvable entityIdField — dispatcher will handle this
        var template = new NotificationTemplate("Title", null,
                NotificationSeverity.INFO, "test", null,
                "entity", "missingField", "actor");
        var input = new SubscriptionInput("user-1", "tenant-1", "Sub",
                "io.casehub.work.workitem.completed",
                List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")),
                false, template, true, null);
        subStore.store(input);

        engine.onStartup(null);

        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                UUID.randomUUID(), "actor-1");

        // Engine fires event — dispatcher will handle template resolution failure
        assertThat(firedEvents).hasSize(1);
    }

    // --- Deregistration ---

    @Test
    void onDataSourceDeregistered_notificationPath_unwiresAll() {
        engine.onStartup(null);
        var sub = subStore.store(subscriptionInput("owner1", "t1", "work.created", true));
        engine.onCreated(new SubscriptionCreated(sub));

        var ds = registry.resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID).orElseThrow();
        var desc = new DataSourceDescriptor(
                NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID,
                new ClassObjectType<>(Object.class), null, Set.of(), Map.of(), Map.of());

        engine.onDataSourceDeregistered(new DataSourceDeregistered(desc, ds));

        pushEvent("work.created", "t1", UUID.randomUUID(), "actor1");
        assertThat(firedEvents).isEmpty();
    }

    @Test
    void onDataSourceDeregistered_otherPath_ignored() {
        engine.onStartup(null);
        var sub = subStore.store(subscriptionInput("owner1", "t1", "work.created", true));
        engine.onCreated(new SubscriptionCreated(sub));

        var otherDesc = new DataSourceDescriptor(
                Path.parse("other/datasource"), PLATFORM_TENANT_ID,
                new ClassObjectType<>(Object.class), null, Set.of(), Map.of(), Map.of());
        DataSource<Object> otherDs = new AlphaDataSource<>();

        engine.onDataSourceDeregistered(new DataSourceDeregistered(otherDesc, otherDs));

        pushEvent("work.created", "t1", UUID.randomUUID(), "actor1");
        assertThat(firedEvents).hasSize(1);
    }

    @Test
    void onCreated_afterDeregistration_skipsWithoutError() {
        engine.onStartup(null);

        var ds = registry.resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID).orElseThrow();
        var desc = new DataSourceDescriptor(
                NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID,
                new ClassObjectType<>(Object.class), null, Set.of(), Map.of(), Map.of());
        engine.onDataSourceDeregistered(new DataSourceDeregistered(desc, ds));

        var sub = subStore.store(subscriptionInput("owner1", "t1", "work.created", true));
        engine.onCreated(new SubscriptionCreated(sub));

        // notificationDataSource is null — no NPE, subscription just not wired
    }

    // --- Helpers ---


    @Test
    void systemSubscription_wiresAndMatchesIdentically() {
        var template = defaultTemplate();
        var input = new SubscriptionInput("admin-1", "tenant-1", "System sub",
                                          "io.casehub.work.workitem.completed",
                                          List.of(), List.of(new NotificationTarget(TargetType.GROUP, "case-managers")),
                                          false, template, true, SubscriptionScope.SYSTEM);
        var storedSub = subStore.store(input);

        engine.onStartup(null);

        pushEvent("io.casehub.work.workitem.completed", "tenant-1",
                  UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).subscription().scope()).isEqualTo(SubscriptionScope.SYSTEM);
    }

    // --- Expression filter tests ---

    @Test
    void event_matchesMvelFilterExpression_firesSubscriptionMatched() {
        var input = new SubscriptionInput("user-1", "tenant-1", "Test sub",
                "work-item.completed",
                List.of((ExpressionEvaluator) new MvelExpressionEvaluator("status == \"completed\"")),
                List.of(new NotificationTarget(TargetType.USER, "user-1")),
                false, defaultTemplate(), true, null);
        subStore.store(input);
        engine.onStartup(null);

        pushEvent("work-item.completed", "tenant-1", UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).hasSize(1);
    }

    @Test
    void event_doesNotMatchMvelFilter_doesNotFire() {
        var input = new SubscriptionInput("user-1", "tenant-1", "Test sub",
                "work-item.completed",
                List.of((ExpressionEvaluator) new MvelExpressionEvaluator("status == \"pending\"")),
                List.of(new NotificationTarget(TargetType.USER, "user-1")),
                false, defaultTemplate(), true, null);
        subStore.store(input);
        engine.onStartup(null);

        pushEvent("work-item.completed", "tenant-1", UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).isEmpty();
    }

    @Test
    void event_dollarMeVariable_substitutedWithOwnerId() {
        var input = new SubscriptionInput("actor-1", "tenant-1", "Test sub",
                "work-item.completed",
                List.of((ExpressionEvaluator) new MvelExpressionEvaluator("actor == $me")),
                List.of(new NotificationTarget(TargetType.USER, "actor-1")),
                false, defaultTemplate(), true, null);
        subStore.store(input);
        engine.onStartup(null);

        pushEvent("work-item.completed", "tenant-1", UUID.randomUUID(), "actor-1");

        assertThat(firedEvents).hasSize(1);
    }

    @Test
    void event_multipleFilters_allMustMatch() {
        var input = new SubscriptionInput("user-1", "tenant-1", "Test sub",
                "work-item.completed",
                List.of(
                        (ExpressionEvaluator) new MvelExpressionEvaluator("status == \"completed\""),
                        (ExpressionEvaluator) new MvelExpressionEvaluator("actor == \"actor-1\"")),
                List.of(new NotificationTarget(TargetType.USER, "user-1")),
                false, defaultTemplate(), true, null);
        subStore.store(input);
        engine.onStartup(null);

        pushEvent("work-item.completed", "tenant-1", UUID.randomUUID(), "actor-1");
        assertThat(firedEvents).hasSize(1);

        firedEvents.clear();
        pushEvent("work-item.completed", "tenant-1", UUID.randomUUID(), "actor-2");
        assertThat(firedEvents).isEmpty();
    }

    // --- Helpers ---

    private SubscriptionInput subscriptionInput(final String ownerId, final String tenancyId,
                                                final String eventType, final boolean enabled) {
        return new SubscriptionInput(ownerId, tenancyId, "Test sub",
                eventType, List.of(),
                List.of(new NotificationTarget(TargetType.USER, ownerId)),
                false, defaultTemplate(), enabled, null);
    }

    private NotificationTemplate defaultTemplate() {
        return new NotificationTemplate("WorkItem {status}", null,
                NotificationSeverity.INFO, "work-item.completed",
                null, "work-item", "workItemId", "actor");
    }

    @SuppressWarnings("unchecked")
    private void pushEvent(final String type, final String tenancyId,
                           final UUID workItemId, final String actor) {
        var ds = registry.resolveSource(NOTIFICATION_DATASOURCE_PATH, PLATFORM_TENANT_ID)
                .orElseThrow();
        ((DataSource<Object>) ds).add(
                new TestEvent(type, tenancyId, workItemId, actor, "completed"));
    }

    // --- Test doubles ---

    record TestEvent(String type, String tenancyId, UUID workItemId,
                     String actor, String status) implements SubscribableEvent {}
}
