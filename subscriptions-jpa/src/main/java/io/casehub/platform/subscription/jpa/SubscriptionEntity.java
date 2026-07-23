package io.casehub.platform.subscription.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import io.casehub.platform.api.subscription.NotificationTarget;
import io.casehub.platform.api.subscription.NotificationTemplate;
import io.casehub.platform.api.subscription.Subscription;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionScope;
import io.casehub.platform.api.util.UUIDv7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "subscription",
       indexes = {
               @Index(name = "idx_subscription_owner_tenant_enabled",
                      columnList = "owner_id, tenancy_id, enabled, created_at DESC"),
               @Index(name = "idx_subscription_enabled",
                      columnList = "enabled")
       })
public class SubscriptionEntity {

    private static final TypeReference<List<Map<String, String>>> FILTER_LIST_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<List<NotificationTarget>>  TARGET_LIST_TYPE =
            new TypeReference<>() {};

    @Id
    public String id;

    @Column(name = "owner_id", nullable = false)
    public String ownerId;

    @Column(name = "tenancy_id", nullable = false)
    public String tenancyId;

    @Column(nullable = false, length = 500)
    public String name;

    @Column(name = "event_type", nullable = false, length = 500)
    public String eventType;

    @Column(name = "filters_json", columnDefinition = "TEXT")
    public String filtersJson;

    @Column(name = "targets_json", nullable = false, columnDefinition = "TEXT")
    public String targetsJson;

    @Column(name = "include_actor", nullable = false)
    public boolean includeActor;

    @Column(name = "template_json", nullable = false, columnDefinition = "TEXT")
    public String templateJson;

    @Column(nullable = false)
    public boolean enabled;
    @Column(nullable = false, length = 10)
    public String  scope;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    static SubscriptionEntity fromInput(SubscriptionInput input, ObjectMapper mapper) {
        SubscriptionEntity entity = new SubscriptionEntity();
        entity.id           = UUIDv7.generate();
        entity.ownerId      = input.ownerId();
        entity.tenancyId    = input.tenancyId();
        entity.name         = input.name();
        entity.eventType    = input.eventType();
        entity.filtersJson  = serializeFilters(input.filters(), mapper);
        entity.targetsJson  = serializeTargets(input.targets(), mapper);
        entity.includeActor = input.includeActor();
        entity.templateJson = serializeTemplate(input.template(), mapper);
        entity.enabled      = input.enabled();
        entity.scope        = input.scope().name();
        Instant now = Instant.now();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    static String serializeFilters(List<ExpressionEvaluator> filters, ObjectMapper mapper) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        try {
            var entries = filters.stream()
                                 .map(f -> Map.of("type", f.type(), "expression", extractExpression(f)))
                                 .toList();
            return mapper.writeValueAsString(entries);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize filters", e);
        }
    }

    static List<ExpressionEvaluator> deserializeFilters(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> entries = mapper.readValue(json, FILTER_LIST_TYPE);
            return List.copyOf(entries.stream()
                                      .map(entry -> (ExpressionEvaluator) switch (entry.get("type")) {
                                          case "mvel" -> new MvelExpressionEvaluator(entry.get("expression"));
                                          case "jq" -> new JQExpressionEvaluator(entry.get("expression"));
                                          default -> throw new IllegalStateException(
                                                  "Unknown filter type: " + entry.get("type"));
                                      })
                                      .toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize filters", e);
        }
    }

    private static String extractExpression(ExpressionEvaluator evaluator) {
        if (evaluator instanceof MvelExpressionEvaluator m) {return m.expression();}
        if (evaluator instanceof JQExpressionEvaluator j) {return j.expression();}
        throw new IllegalArgumentException("Unknown evaluator type: " + evaluator.type());
    }

    static String serializeTemplate(NotificationTemplate template, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(template);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize template", e);
        }
    }

    static NotificationTemplate deserializeTemplate(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, NotificationTemplate.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize template", e);
        }
    }

    static String serializeTargets(List<NotificationTarget> targets, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(targets);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize targets", e);
        }
    }

    static List<NotificationTarget> deserializeTargets(String json, ObjectMapper mapper) {
        try {
            return List.copyOf(mapper.readValue(json, TARGET_LIST_TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize targets", e);
        }
    }

    Subscription toSubscription(ObjectMapper mapper) {
        return new Subscription(
                id,
                ownerId,
                tenancyId,
                name,
                eventType,
                deserializeFilters(filtersJson, mapper),
                deserializeTargets(targetsJson, mapper),
                includeActor,
                deserializeTemplate(templateJson, mapper),
                enabled,
                SubscriptionScope.valueOf(scope),
                createdAt,
                updatedAt
        );
    }
}
