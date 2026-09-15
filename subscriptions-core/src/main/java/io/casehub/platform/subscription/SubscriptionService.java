package io.casehub.platform.subscription;

import io.casehub.platform.api.expression.ExpressionEngineRegistry;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
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

import java.util.List;
import java.util.Optional;

public class SubscriptionService {

    private final SubscriptionStore store;
    private final CurrentPrincipal principal;
    private final ExpressionEngineRegistry expressionRegistry;

    public SubscriptionService(SubscriptionStore store,
                               CurrentPrincipal principal,
                               ExpressionEngineRegistry expressionRegistry) {
        this.store = store;
        this.principal = principal;
        this.expressionRegistry = expressionRegistry;
    }

    public Subscription create(SubscriptionInput input) {
        var effectiveScope = input.scope();

        if (effectiveScope == SubscriptionScope.SYSTEM) {
            if (!principal.hasGroup(SubscriptionConstants.SYSTEM_SUBSCRIPTION_ADMIN_GROUP)) {
                throw new SecurityException("SYSTEM scope requires admin group membership");
            }
            if (input.targets() == null || input.targets().isEmpty()) {
                throw new IllegalArgumentException("SYSTEM scope requires explicit targets");
            }
            for (var filter : input.filters()) {
                String expr = extractExpression(filter);
                if (expr.contains("$me")) {
                    throw new IllegalArgumentException("$me filter not allowed for SYSTEM scope");
                }
            }
        }

        final var targets = (effectiveScope != SubscriptionScope.SYSTEM
                             && (input.targets() == null || input.targets().isEmpty()))
                            ? List.of(new NotificationTarget(TargetType.USER, principal.actorId()))
                            : input.targets();

        final var securedInput = new SubscriptionInput(
                principal.actorId(),
                principal.tenancyId(),
                input.name(),
                input.eventType(),
                input.filters(),
                targets,
                input.includeActor(),
                input.template(),
                input.enabled(),
                effectiveScope
        );
        for (var filter : securedInput.filters()) {
            try {
                expressionRegistry.validate(filter.type(), extractExpression(filter));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid filter expression: " + e.getMessage(), e);
            }
        }

        return store.store(securedInput);
    }

    public SubscriptionPage list(Boolean enabled, SubscriptionScope scope, String cursor, int limit) {
        return store.find(new SubscriptionQuery(
                scope == SubscriptionScope.SYSTEM ? null : principal.actorId(),
                principal.tenancyId(),
                scope,
                enabled,
                cursor,
                limit
        ));
    }

    public Optional<Subscription> getById(String id) {
        return store.findById(id, principal.actorId(), principal.tenancyId());
    }

    public Optional<Subscription> update(String id, SubscriptionUpdate update) {
        var opt = store.findById(id, principal.actorId(), principal.tenancyId());
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        checkSystemAccess(opt.get().scope());
        return store.update(id, principal.actorId(), principal.tenancyId(), update);
    }

    public boolean delete(String id) {
        var opt = store.findById(id, principal.actorId(), principal.tenancyId());
        if (opt.isEmpty()) {
            return false;
        }
        checkSystemAccess(opt.get().scope());
        return store.delete(id, principal.actorId(), principal.tenancyId());
    }

    public Optional<Subscription> enable(String id) {
        var opt = store.findById(id, principal.actorId(), principal.tenancyId());
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        checkSystemAccess(opt.get().scope());
        return store.update(id, principal.actorId(), principal.tenancyId(),
                new SubscriptionUpdate(null, null, null, null, null, null, true));
    }

    public Optional<Subscription> disable(String id) {
        var opt = store.findById(id, principal.actorId(), principal.tenancyId());
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        checkSystemAccess(opt.get().scope());
        return store.update(id, principal.actorId(), principal.tenancyId(),
                new SubscriptionUpdate(null, null, null, null, null, null, false));
    }

    private void checkSystemAccess(SubscriptionScope scope) {
        if (scope == SubscriptionScope.SYSTEM
            && !principal.hasGroup(SubscriptionConstants.SYSTEM_SUBSCRIPTION_ADMIN_GROUP)) {
            throw new SecurityException("Unauthorized access to SYSTEM scope subscription");
        }
    }

    static String extractExpression(ExpressionEvaluator evaluator) {
        if (evaluator instanceof MvelExpressionEvaluator m) { return m.expression(); }
        if (evaluator instanceof JQExpressionEvaluator j) { return j.expression(); }
        throw new IllegalArgumentException("Unknown evaluator type: " + evaluator.type());
    }
}
