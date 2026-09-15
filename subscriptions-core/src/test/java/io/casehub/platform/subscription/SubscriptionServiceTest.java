package io.casehub.platform.subscription;

import io.casehub.platform.api.expression.ExpressionEngineRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.subscription.NotificationTarget;
import io.casehub.platform.api.subscription.Subscription;
import io.casehub.platform.api.subscription.SubscriptionConstants;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionPage;
import io.casehub.platform.api.subscription.SubscriptionQuery;
import io.casehub.platform.api.subscription.SubscriptionScope;
import io.casehub.platform.api.subscription.SubscriptionStore;
import io.casehub.platform.api.subscription.SubscriptionUpdate;
import io.casehub.platform.api.subscription.TargetType;
import org.junit.jupiter.api.Test;

import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.subscription.NotificationTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionServiceTest {

    private static final NotificationTemplate TEMPLATE = new NotificationTemplate(
            "title", "body", NotificationSeverity.INFO, "cat", null, "entity", "entityId", "actorId");

    private final StubStore store = new StubStore();

    private final CurrentPrincipal principal = new CurrentPrincipal() {
        @Override public String actorId() { return "user-1"; }
        @Override public String tenancyId() { return "tenant-1"; }
        @Override public boolean hasGroup(String group) {
            return SubscriptionConstants.SYSTEM_SUBSCRIPTION_ADMIN_GROUP.equals(group);
        }
        @Override public Set<String> groups() { return Set.of(SubscriptionConstants.SYSTEM_SUBSCRIPTION_ADMIN_GROUP); }
        @Override public boolean isCrossTenantAdmin() { return false; }
    };

    private final CurrentPrincipal nonAdminPrincipal = new CurrentPrincipal() {
        @Override public String actorId() { return "user-2"; }
        @Override public String tenancyId() { return "tenant-1"; }
        @Override public boolean hasGroup(String group) { return false; }
        @Override public Set<String> groups() { return Set.of(); }
        @Override public boolean isCrossTenantAdmin() { return false; }
    };

    private final ExpressionEngineRegistry expressionRegistry = new ExpressionEngineRegistry() {
        @Override public void register(io.casehub.platform.api.expression.ExpressionEngine engine) {}
        @Override public java.util.Optional<io.casehub.platform.api.expression.ExpressionEngine> resolve(String type) { return java.util.Optional.empty(); }
        @Override public void validate(String type, String expression) {}
        @Override public <C, R> io.casehub.platform.api.expression.CompiledExpression<C, R> compile(String type, String expression, Class<C> ct, Class<R> rt) { return null; }
        @Override public <C, R> io.casehub.platform.api.expression.CompiledExpression<C, R> compile(String type, String expression, Class<C> ct, Class<R> rt, java.util.Map<String, Object> variables) { return null; }
    };

    @Test
    void create_system_scope_without_admin_throws() {
        var service = new SubscriptionService(store, nonAdminPrincipal, expressionRegistry);
        var input = new SubscriptionInput("caller", "t1", "test", "test.event",
                List.of(), List.of(new NotificationTarget(TargetType.USER, "x")),
                false, TEMPLATE, true, SubscriptionScope.SYSTEM);

        assertThatThrownBy(() -> service.create(input))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void create_user_scope_auto_adds_principal_target() {
        var service = new SubscriptionService(store, principal, expressionRegistry);
        var input = new SubscriptionInput("caller", "t1", "test", "test.event",
                List.of(), List.of(), false, TEMPLATE, true, SubscriptionScope.USER);

        var result = service.create(input);
        assertThat(result).isNotNull();
        assertThat(store.lastInput.targets()).hasSize(1);
        assertThat(store.lastInput.targets().get(0).id()).isEqualTo("user-1");
    }

    @Test
    void list_delegates_to_store() {
        var service = new SubscriptionService(store, principal, expressionRegistry);
        var result = service.list(null, null, null, 25);
        assertThat(result).isNotNull();
    }

    @Test
    void getById_returns_optional() {
        var service = new SubscriptionService(store, principal, expressionRegistry);
        assertThat(service.getById("missing")).isEmpty();
    }

    @Test
    void delete_system_scope_non_admin_throws() {
        store.systemSubscription = true;
        var service = new SubscriptionService(store, nonAdminPrincipal, expressionRegistry);

        assertThatThrownBy(() -> service.delete("sub-1"))
                .isInstanceOf(SecurityException.class);
    }

    static class StubStore implements SubscriptionStore {
        SubscriptionInput lastInput;
        boolean systemSubscription = false;

        @Override
        public Subscription store(SubscriptionInput input) {
            lastInput = input;
            return new Subscription("sub-1", input.ownerId(), input.tenancyId(),
                    input.name(), input.eventType(), input.filters(), input.targets(),
                    input.includeActor(), input.template(), input.enabled(), input.scope(),
                    Instant.now(), Instant.now());
        }

        @Override
        public Optional<Subscription> findById(String id, String ownerId, String tenancyId) {
            if ("sub-1".equals(id)) {
                return Optional.of(new Subscription(id, ownerId, tenancyId, "test", "evt",
                        List.of(), List.of(), false, TEMPLATE, true,
                        systemSubscription ? SubscriptionScope.SYSTEM : SubscriptionScope.USER,
                        Instant.now(), Instant.now()));
            }
            return Optional.empty();
        }

        @Override
        public SubscriptionPage find(SubscriptionQuery query) {
            return new SubscriptionPage(List.of(), null);
        }

        @Override
        public Optional<Subscription> update(String id, String ownerId, String tenancyId, SubscriptionUpdate update) {
            return findById(id, ownerId, tenancyId);
        }

        @Override
        public boolean delete(String id, String ownerId, String tenancyId) {
            return "sub-1".equals(id);
        }

        @Override
        public Stream<Subscription> findAllEnabled() {
            return Stream.empty();
        }
    }
}
