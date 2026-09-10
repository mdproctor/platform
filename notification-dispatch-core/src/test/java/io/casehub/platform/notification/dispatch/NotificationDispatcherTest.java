package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.DeliveryChannelDescriptor;
import io.casehub.platform.api.delivery.DestinationScope;
import io.casehub.platform.api.delivery.DeliveryChannels;
import io.casehub.platform.api.delivery.DeliveryResult;
import io.casehub.platform.api.delivery.DigestBuffer;
import io.casehub.platform.api.delivery.DigestBufferKey;
import io.casehub.platform.api.delivery.DigestSchedule;
import io.casehub.platform.api.delivery.NotificationDeliverer;
import io.casehub.platform.api.identity.GroupMember;
import io.casehub.platform.api.identity.GroupMembershipProvider;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.settings.MuteRule;
import io.casehub.platform.api.notification.settings.MuteRuleInput;
import io.casehub.platform.api.notification.settings.MuteScope;
import io.casehub.platform.api.notification.settings.NotificationPreferenceStore;
import io.casehub.platform.api.notification.settings.NotificationPreferences;
import io.casehub.platform.api.notification.settings.Snooze;
import io.casehub.platform.api.notification.settings.SnoozeInput;
import io.casehub.platform.api.notification.settings.SuppressionStore;
import io.casehub.platform.api.subscription.EntityWatcherProvider;
import io.casehub.platform.api.subscription.NotificationTarget;
import io.casehub.platform.api.subscription.NotificationTemplate;
import io.casehub.platform.api.subscription.Subscription;
import io.casehub.platform.api.subscription.SubscriptionMatched;
import io.casehub.platform.api.subscription.SubscriptionScope;
import io.casehub.platform.api.subscription.TargetType;
import io.casehub.platform.delivery.channel.inmem.InMemoryDeliveryChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDispatcherTest {

    private static final String TENANT = "tenant-1";
    private static final Instant NOW = Instant.now();

    private static final NotificationTemplate TEMPLATE = new NotificationTemplate(
            "WorkItem {status}", null, NotificationSeverity.INFO, "work-item.updated",
            null, "work-item", "entityId", "actorId");

    // Capture deliveries
    private final List<NotificationInput> deliveredNotifications = new ArrayList<>();

    // Stores
    private StubNotificationPreferenceStore preferenceStore;
    private StubSuppressionStore suppressionStore;
    private InMemoryDeliveryChannelRegistry channelRegistry;
    private CapturingDigestBuffer digestBuffer;

    // Pipeline components
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        preferenceStore = new StubNotificationPreferenceStore();
        suppressionStore = new StubSuppressionStore();
        channelRegistry = new InMemoryDeliveryChannelRegistry();
        digestBuffer = new CapturingDigestBuffer();
        deliveredNotifications.clear();

        // Register in-app channel with capturing deliverer
        channelRegistry.register(
                new DeliveryChannelDescriptor(DeliveryChannels.IN_APP, "In-App Inbox",
                        false, true, NotificationSeverity.INFO, null, null, null),
                new CapturingDeliverer(DeliveryChannels.IN_APP, deliveredNotifications));

        final GroupMembershipProvider groupProvider = (groupName, tenancyId) -> {
            if ("team-alpha".equals(groupName)) {
                return Set.of(
                        new GroupMember("user-alice", "Alice"),
                        new GroupMember("user-bob", "Bob"));
            }
            return Set.of();
        };

        final EntityWatcherProvider entityWatcherProvider = (entityType, entityId, tenancyId) -> Set.of();
        final var targetResolver = new TargetResolver(groupProvider, entityWatcherProvider);
        final var suppressionEvaluator = new SuppressionEvaluator();
        final var channelRouter = new ChannelRouter(channelRegistry);

        var deliveryAttemptStore = new io.casehub.platform.delivery.tracking.inmem.InMemoryDeliveryAttemptStore(10000);
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.findAndRegisterModules();
        var deliveryTracker = new DeliveryTracker(deliveryAttemptStore, objectMapper, java.time.Duration.ofSeconds(30));

        dispatcher = new NotificationDispatcher(
                targetResolver, suppressionEvaluator, channelRouter,
                preferenceStore, suppressionStore, digestBuffer, deliveryTracker);
    }

    @Test
    void dispatch_userTarget_createsNotification() {
        var sub = subscription(
                List.of(new NotificationTarget(TargetType.USER, "user-recipient")),
                false);
        var pojo = new TestEvent("wi-1", "actor-user", "completed");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        assertThat(deliveredNotifications).hasSize(1);
        assertThat(deliveredNotifications.get(0).userId()).isEqualTo("user-recipient");
        assertThat(deliveredNotifications.get(0).tenancyId()).isEqualTo(TENANT);
        assertThat(deliveredNotifications.get(0).title()).isEqualTo("WorkItem completed");
    }

    @Test
    void dispatch_groupTarget_expandsAndCreates() {
        var sub = subscription(
                List.of(new NotificationTarget(TargetType.GROUP, "team-alpha")),
                false);
        var pojo = new TestEvent("wi-1", "actor-external", "in-progress");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        assertThat(deliveredNotifications).hasSize(2);
        assertThat(deliveredNotifications).extracting(NotificationInput::userId)
                .containsExactlyInAnyOrder("user-alice", "user-bob");
    }

    @Test
    void dispatch_mutedUser_dropsNotification() {
        // Mute user-recipient for entity wi-1
        suppressionStore.muteRules.add(new MuteRule(
                "m-1", "user-recipient", TENANT, MuteScope.ENTITY,
                "wi-1", "work-item", NOW, null));

        var sub = subscription(
                List.of(new NotificationTarget(TargetType.USER, "user-recipient")),
                false);
        var pojo = new TestEvent("wi-1", "actor-user", "completed");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        assertThat(deliveredNotifications).isEmpty();
    }

    @Test
    void dispatch_snoozedUser_createsInApp() {
        // Snooze user — external channels would be suppressed but in-app proceeds
        suppressionStore.snooze = new Snooze(
                "user-recipient", TENANT,
                NOW.plus(1, ChronoUnit.HOURS), NOW);

        var sub = subscription(
                List.of(new NotificationTarget(TargetType.USER, "user-recipient")),
                false);
        var pojo = new TestEvent("wi-1", "actor-user", "completed");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        // In-app is internal, not suppressed by snooze — notification delivered
        assertThat(deliveredNotifications).hasSize(1);
    }

    @Test
    void dispatch_nullTemplateResolution_skipsRecipient() {
        // Template references a field that doesn't exist on the POJO
        var badTemplate = new NotificationTemplate(
                "Title", null, NotificationSeverity.INFO, "test",
                null, "work-item", "missingField", "actorId");
        var sub = new Subscription(
                "sub-1", "owner-1", TENANT, "Test", "test.event",
                List.of(),
                List.of(new NotificationTarget(TargetType.USER, "user-recipient")),
                false, badTemplate, true, SubscriptionScope.USER, NOW, NOW);
        var pojo = new TestEvent("wi-1", "actor-user", "completed");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        assertThat(deliveredNotifications).isEmpty();
    }

    @Test
    void dispatch_excludesActor_byDefault() {
        var sub = subscription(
                List.of(new NotificationTarget(TargetType.USER, "the-actor")),
                false);
        var pojo = new TestEvent("wi-1", "the-actor", "completed");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        // Actor is excluded — no notification
        assertThat(deliveredNotifications).isEmpty();
    }

    @Test
    void dispatch_includesActor_whenIncludeActorTrue() {
        var sub = subscription(
                List.of(new NotificationTarget(TargetType.USER, "the-actor")),
                true);
        var pojo = new TestEvent("wi-1", "the-actor", "completed");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        assertThat(deliveredNotifications).hasSize(1);
        assertThat(deliveredNotifications.get(0).userId()).isEqualTo("the-actor");
    }

    @Test
    void dispatch_multipleRecipients_eachGetsNotification() {
        var sub = subscription(
                List.of(
                        new NotificationTarget(TargetType.USER, "user-1"),
                        new NotificationTarget(TargetType.USER, "user-2"),
                        new NotificationTarget(TargetType.USER, "user-3")),
                false);
        var pojo = new TestEvent("wi-1", "actor-external", "completed");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        assertThat(deliveredNotifications).hasSize(3);
        assertThat(deliveredNotifications).extracting(NotificationInput::userId)
                .containsExactlyInAnyOrder("user-1", "user-2", "user-3");
    }

    @Test
    void dispatch_channelDeliveryFailure_doesNotBlockOtherChannels() {
        // Register a failing external channel too
        var failingDeliverer = new FailingDeliverer("email");
        channelRegistry.register(
                new DeliveryChannelDescriptor(DeliveryChannels.EMAIL, "Email",
                        true, true, NotificationSeverity.INFO, null, null, null),
                failingDeliverer);

        var sub = subscription(
                List.of(new NotificationTarget(TargetType.USER, "user-recipient")),
                false);
        var pojo = new TestEvent("wi-1", "actor-user", "completed");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        // In-app should still succeed despite email failure
        assertThat(deliveredNotifications).hasSize(1);
    }

    @Test
    void dispatch_digestedChannel_buffersInsteadOfDelivering() {
        // Register email with digest schedule
        channelRegistry.register(
                new DeliveryChannelDescriptor(DeliveryChannels.EMAIL, "Email",
                        true, true, NotificationSeverity.INFO,
                        new DigestSchedule.Interval(Duration.ofMinutes(1)), null, null),
                new CapturingDeliverer(DeliveryChannels.EMAIL, deliveredNotifications));

        var sub = subscription(
                List.of(new NotificationTarget(TargetType.USER, "user-recipient")),
                false);
        var pojo = new TestEvent("wi-1", "actor-user", "completed");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        // In-app delivered immediately
        assertThat(deliveredNotifications).hasSize(1);
        assertThat(deliveredNotifications.get(0).userId()).isEqualTo("user-recipient");

        // Email buffered, not delivered
        assertThat(digestBuffer.buffered).hasSize(1);
        assertThat(digestBuffer.buffered.get(0).key().channelId()).isEqualTo(DeliveryChannels.EMAIL);
    }

    @Test
    void dispatch_urgentSeverity_bypassesDigest() {
        channelRegistry.register(
                new DeliveryChannelDescriptor(DeliveryChannels.EMAIL, "Email",
                        true, true, NotificationSeverity.INFO,
                        new DigestSchedule.Interval(Duration.ofMinutes(1)), null, null),
                new CapturingDeliverer(DeliveryChannels.EMAIL, deliveredNotifications));

        var urgentTemplate = new NotificationTemplate(
                "URGENT: {status}", null, NotificationSeverity.URGENT, "urgent.event",
                null, "work-item", "entityId", "actorId");
        var sub = new Subscription("sub-1", "owner-1", TENANT, "Urgent", "test.event",
                List.of(),
                List.of(new NotificationTarget(TargetType.USER, "user-recipient")),
                false, urgentTemplate, true, SubscriptionScope.USER, NOW, NOW);
        var pojo = new TestEvent("wi-1", "actor-user", "critical");

        dispatcher.onMatch(new SubscriptionMatched(sub, pojo));

        // Both in-app and email delivered immediately (URGENT bypasses digest)
        assertThat(deliveredNotifications).hasSize(2);
        assertThat(digestBuffer.buffered).isEmpty();
    }

    @Test
    void dispatch_perTenantChannel_deliversOnceForMultipleRecipients() {
        var slackCaptured = new ArrayList<NotificationInput>();
        channelRegistry.register(
                new DeliveryChannelDescriptor("slack", "Slack", true, true,
                                              NotificationSeverity.INFO, null, null,
                                              DestinationScope.PER_TENANT),
                new CapturingDeliverer("slack", slackCaptured));

        var targets = List.of(
                new NotificationTarget(TargetType.USER, "user1"),
                new NotificationTarget(TargetType.USER, "user2"),
                new NotificationTarget(TargetType.USER, "user3"));
        var sub = subscription(targets, true);
        dispatcher.onMatch(new SubscriptionMatched(sub,
                                                   new TestEvent("E1", "actor1", "open")));

        assertThat(slackCaptured).hasSize(1);
        assertThat(deliveredNotifications).hasSize(3);
    }

    @Test
    void dispatch_perTenantChannel_mixedWithPerUser_bothWork() {
        var slackCaptured = new ArrayList<NotificationInput>();
        var emailCaptured = new ArrayList<NotificationInput>();
        channelRegistry.register(
                new DeliveryChannelDescriptor("slack", "Slack", true, true,
                                              NotificationSeverity.INFO, null, null,
                                              DestinationScope.PER_TENANT),
                new CapturingDeliverer("slack", slackCaptured));
        channelRegistry.register(
                new DeliveryChannelDescriptor(DeliveryChannels.EMAIL, "Email", true, true,
                                              NotificationSeverity.INFO, null, null, null),
                new CapturingDeliverer(DeliveryChannels.EMAIL, emailCaptured));

        var targets = List.of(
                new NotificationTarget(TargetType.USER, "user1"),
                new NotificationTarget(TargetType.USER, "user2"));
        dispatcher.onMatch(new SubscriptionMatched(subscription(targets, true),
                                                   new TestEvent("E1", "actor1", "open")));

        assertThat(slackCaptured).hasSize(1);
        assertThat(emailCaptured).hasSize(2);
    }

    @Test
    void dispatch_perTenantChannel_deliveryFailure_propagatesFailure() {
        channelRegistry.register(
                new DeliveryChannelDescriptor("slack", "Slack", true, true,
                                              NotificationSeverity.INFO, null, null,
                                              DestinationScope.PER_TENANT),
                new FailingDeliverer("slack"));

        var targets = List.of(
                new NotificationTarget(TargetType.USER, "user1"),
                new NotificationTarget(TargetType.USER, "user2"));
        dispatcher.onMatch(new SubscriptionMatched(subscription(targets, true),
                                                   new TestEvent("E1", "actor1", "open")));

        assertThat(deliveredNotifications).hasSize(2);
    }

    @Test
    void dispatch_perTenantChannel_suppressedUserSkipped_nextUserDelivers() {
        var slackCaptured = new ArrayList<NotificationInput>();
        channelRegistry.register(
                new DeliveryChannelDescriptor("slack", "Slack", true, true,
                                              NotificationSeverity.INFO, null, null,
                                              DestinationScope.PER_TENANT),
                new CapturingDeliverer("slack", slackCaptured));

        suppressionStore.snooze = new Snooze(
                "user1", TENANT,
                NOW.plus(1, ChronoUnit.HOURS), NOW);

        var targets = List.of(
                new NotificationTarget(TargetType.USER, "user1"),
                new NotificationTarget(TargetType.USER, "user2"));
        dispatcher.onMatch(new SubscriptionMatched(subscription(targets, true),
                                                   new TestEvent("E1", "actor1", "open")));

        assertThat(slackCaptured).hasSize(1);
        assertThat(slackCaptured.getFirst().userId()).isEqualTo("user2");
    }


    // --- helpers ---

    private Subscription subscription(List<NotificationTarget> targets, boolean includeActor) {
        return new Subscription(
                "sub-1", "owner-1", TENANT, "Test Sub", "test.event",
                List.of(), targets, includeActor, TEMPLATE, true, SubscriptionScope.USER, NOW, NOW);
    }

    record TestEvent(String entityId, String actorId, String status) {}

    // --- stubs ---

    private static final class CapturingDeliverer implements NotificationDeliverer {
        private final String channel;
        private final List<NotificationInput> captured;

        CapturingDeliverer(String channel, List<NotificationInput> captured) {
            this.channel = channel;
            this.captured = captured;
        }

        @Override
        public String channelId() {
            return channel;
        }

        @Override
        public DeliveryResult deliver(NotificationInput notification) {
            captured.add(notification);
            return new DeliveryResult(true, null);
        }
    }

    private static final class FailingDeliverer implements NotificationDeliverer {
        private final String channel;

        FailingDeliverer(String channel) {
            this.channel = channel;
        }

        @Override
        public String channelId() {
            return channel;
        }

        @Override
        public DeliveryResult deliver(NotificationInput notification) {
            throw new RuntimeException("Delivery failed!");
        }
    }

    private static final class StubNotificationPreferenceStore implements NotificationPreferenceStore {
        NotificationPreferences preferences;

        @Override
        public Optional<NotificationPreferences> get(String userId, String tenancyId) {
            return Optional.ofNullable(preferences);
        }

        @Override
        public NotificationPreferences update(String userId, String tenancyId,
                                              io.casehub.platform.api.notification.settings.NotificationPreferenceUpdate update) {
            throw new UnsupportedOperationException("Not used in tests");
        }
    }

    private static final class StubSuppressionStore implements SuppressionStore {
        final List<MuteRule> muteRules = new ArrayList<>();
        Snooze snooze;

        @Override
        public MuteRule addMute(MuteRuleInput input) {
            throw new UnsupportedOperationException("Not used in tests");
        }

        @Override
        public List<MuteRule> activeMutes(String userId, String tenancyId) {
            return muteRules.stream()
                    .filter(r -> r.userId().equals(userId) && r.tenancyId().equals(tenancyId))
                    .toList();
        }

        @Override
        public boolean removeMute(String muteId, String userId, String tenancyId) {
            throw new UnsupportedOperationException("Not used in tests");
        }

        @Override
        public Snooze activateSnooze(SnoozeInput input) {
            throw new UnsupportedOperationException("Not used in tests");
        }

        @Override
        public Optional<Snooze> activeSnooze(String userId, String tenancyId) {
            if (snooze != null && snooze.userId().equals(userId) && snooze.tenancyId().equals(tenancyId)) {
                return Optional.of(snooze);
            }
            return Optional.empty();
        }

        @Override
        public boolean cancelSnooze(String userId, String tenancyId) {
            throw new UnsupportedOperationException("Not used in tests");
        }
    }

    private static final class CapturingDigestBuffer implements DigestBuffer {
        record BufferedItem(DigestBufferKey key, NotificationInput notification) {}
        final List<BufferedItem> buffered = new ArrayList<>();

        @Override
        public void add(DigestBufferKey key, NotificationInput notification) {
            buffered.add(new BufferedItem(key, notification));
        }

        @Override
        public List<NotificationInput> drain(DigestBufferKey key) {
            var items = buffered.stream()
                    .filter(b -> b.key().equals(key))
                    .map(BufferedItem::notification)
                    .toList();
            buffered.removeIf(b -> b.key().equals(key));
            return items;
        }

        @Override
        public Set<DigestBufferKey> pendingKeys() {
            return buffered.stream().map(BufferedItem::key).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public Optional<Instant> oldestPendingTimestamp(DigestBufferKey key) {
            return Optional.of(Instant.now());
        }

        @Override
        public int pendingCount(DigestBufferKey key) {
            return (int) buffered.stream().filter(b -> b.key().equals(key)).count();
        }

        @Override
        public Set<DigestBufferKey> pendingKeysForUser(String userId, String tenancyId) {
            return buffered.stream()
                    .map(BufferedItem::key)
                    .filter(key -> key.userId().equals(userId) && key.tenancyId().equals(tenancyId))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
}
