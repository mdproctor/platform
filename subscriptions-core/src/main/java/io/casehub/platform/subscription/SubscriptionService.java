package io.casehub.platform.subscription;

import io.casehub.platform.api.expression.ExpressionEngineRegistry;
import io.casehub.platform.api.mcp.HttpMethod;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestMethod;
import io.casehub.platform.api.mcp.RestPath;
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

@McpDomain(value = "subscriptions", basePath = "/subscriptions")
public class SubscriptionService {

    private final SubscriptionStore        store;
    private final CurrentPrincipal         principal;
    private final ExpressionEngineRegistry expressionRegistry;

    public SubscriptionService(SubscriptionStore store,
                               CurrentPrincipal principal,
                               ExpressionEngineRegistry expressionRegistry) {
        this.store              = store;
        this.principal          = principal;
        this.expressionRegistry = expressionRegistry;
    }

    @PlatformMutation("Create a subscription")
    @RestPath("/")
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

    @PlatformQuery("List subscriptions")
    @RestPath("/")
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

    @PlatformQuery("Get subscription by ID")
    @RestPath("/{id}")
    public Optional<Subscription> getById(@PathParam String id) {
        return store.findById(id, principal.actorId(), principal.tenancyId());
    }

    @PlatformMutation("Update a subscription")
    @RestMethod(HttpMethod.PATCH)
    @RestPath("/{id}")
    public Optional<Subscription> update(@PathParam String id, SubscriptionUpdate update) {
        var opt = store.findById(id, principal.actorId(), principal.tenancyId());
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        checkSystemAccess(opt.get().scope());
        return store.update(id, principal.actorId(), principal.tenancyId(), update);
    }

    @PlatformMutation("Delete a subscription")
    @RestMethod(HttpMethod.DELETE)
    @RestPath("/{id}")
    public boolean delete(@PathParam String id) {
        var opt = store.findById(id, principal.actorId(), principal.tenancyId());
        if (opt.isEmpty()) {
            return false;
        }
        checkSystemAccess(opt.get().scope());
        return store.delete(id, principal.actorId(), principal.tenancyId());
    }

    @PlatformMutation("Enable a subscription")
    @RestMethod(HttpMethod.PATCH)
    @RestPath("/{id}/enable")
    public Optional<Subscription> enable(@PathParam String id) {
        var opt = store.findById(id, principal.actorId(), principal.tenancyId());
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        checkSystemAccess(opt.get().scope());
        return store.update(id, principal.actorId(), principal.tenancyId(),
                            new SubscriptionUpdate(null, null, null, null, null, null, true));
    }

    @PlatformMutation("Disable a subscription")
    @RestMethod(HttpMethod.PATCH)
    @RestPath("/{id}/disable")
    public Optional<Subscription> disable(@PathParam String id) {
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
        if (evaluator instanceof MvelExpressionEvaluator m) {return m.expression();}
        if (evaluator instanceof JQExpressionEvaluator j) {return j.expression();}
        throw new IllegalArgumentException("Unknown evaluator type: " + evaluator.type());
    }
}
