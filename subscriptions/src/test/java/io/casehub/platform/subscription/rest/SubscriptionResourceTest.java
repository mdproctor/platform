package io.casehub.platform.subscription.rest;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import io.casehub.platform.api.subscription.NotificationTarget;
import io.casehub.platform.api.subscription.NotificationTemplate;
import io.casehub.platform.api.subscription.SubscriptionStore;
import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionScope;
import io.casehub.platform.api.subscription.SubscriptionUpdate;
import io.casehub.platform.api.subscription.TargetType;
import io.casehub.platform.subscription.inmem.InMemorySubscriptionStore;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class SubscriptionResourceTest {

    @Inject
    FixedCurrentPrincipal principal;

    @Inject
    SubscriptionStore store;

    @Inject
    InMemorySubscriptionStore inMemoryStore;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        principal.reset();
        principal.setActorId("user-1");
        principal.setTenancyId(TenancyConstants.DEFAULT_TENANT_ID);
        inMemoryStore.clear();
        io.restassured.RestAssured.config = io.restassured.RestAssured.config()
                .objectMapperConfig(io.restassured.config.ObjectMapperConfig.objectMapperConfig()
                        .jackson2ObjectMapperFactory((type, s) -> objectMapper));
    }

    @Test
    void create_returnsSubscriptionWithGeneratedId() {
        // Given: subscription input
        var template = new NotificationTemplate(
            "Work item {subject} created",
            "A new work item was created",
            NotificationSeverity.INFO,
            "work-item.created",
            "/work-items/{entityId}",
            "work-item",
            "entityId",
            "actorId"
        );
        var input = new SubscriptionInput(
            "user-1",
            TenancyConstants.DEFAULT_TENANT_ID,
            "Work item notifications",
            "io.casehub.work.item.created",
            List.of((ExpressionEvaluator) new MvelExpressionEvaluator("priority == 'HIGH'")),
            List.of(new NotificationTarget(TargetType.USER, "user-1")),
            false,
            template,
            true,
            null
        );

        // When: create subscription
        given()
            .contentType(ContentType.JSON)
            .body(input)
            .when()
            .post("/subscriptions")
            .then()
            .statusCode(201)
            .contentType(ContentType.JSON)
            .body("id", notNullValue())
            .body("ownerId", equalTo("user-1"))
            .body("tenancyId", equalTo(TenancyConstants.DEFAULT_TENANT_ID))
            .body("name", equalTo("Work item notifications"))
            .body("eventType", equalTo("io.casehub.work.item.created"))
            .body("enabled", equalTo(true))
            .body("createdAt", notNullValue())
            .body("updatedAt", notNullValue());
    }

    @Test
    void list_returnsSubscriptionsForCurrentUser() {
        // Given: subscription for user-1
        var template = new NotificationTemplate(
            "Notification title",
            null,
            NotificationSeverity.INFO,
            "test.category",
            null,
            "test-entity",
            "entityId",
            "actorId"
        );
        var input = new SubscriptionInput("user-1", TenancyConstants.DEFAULT_TENANT_ID, "Test subscription", "test.event.type", List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")), false, template, true, null
        );
        store.store(input);

        // When: list subscriptions
        given()
            .when()
            .get("/subscriptions")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("subscriptions", hasSize(1))
            .body("subscriptions[0].name", equalTo("Test subscription"))
            .body("subscriptions[0].ownerId", equalTo("user-1"))
            .body("nextCursor", nullValue());
    }

    @Test
    void list_filtersByEnabled() {
        // Given: one enabled, one disabled subscription
        var template = new NotificationTemplate(
            "Title",
            null,
            NotificationSeverity.INFO,
            "category",
            null,
            "entity",
            "entityId",
            "actorId"
        );

        var enabledInput = new SubscriptionInput("user-1", TenancyConstants.DEFAULT_TENANT_ID, "Enabled subscription", "test.enabled", List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")), false, template, true, null
        );
        store.store(enabledInput);

        var disabledInput = new SubscriptionInput("user-1", TenancyConstants.DEFAULT_TENANT_ID, "Disabled subscription", "test.disabled", List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")), false, template, false, null
        );
        store.store(disabledInput);

        // When: list with enabled=true filter
        given()
            .queryParam("enabled", true)
            .when()
            .get("/subscriptions")
            .then()
            .statusCode(200)
            .body("subscriptions", hasSize(1))
            .body("subscriptions[0].name", equalTo("Enabled subscription"))
            .body("subscriptions[0].enabled", equalTo(true));
    }

    @Test
    void list_respectsPaginationLimit() {
        // Given: 3 subscriptions
        var template = new NotificationTemplate(
            "Title",
            null,
            NotificationSeverity.INFO,
            "category",
            null,
            "entity",
            "entityId",
            "actorId"
        );

        for (int i = 1; i <= 3; i++) {
            var input = new SubscriptionInput(
                "user-1",
                TenancyConstants.DEFAULT_TENANT_ID,
                "Subscription " + i,
                "test.type." + i,
                List.of(),
                List.of(new NotificationTarget(TargetType.USER, "user-1")),
                false,
                template,
                true,
                null
            );
            store.store(input);
        }

        // When: list with limit=2
        given()
            .queryParam("limit", 2)
            .when()
            .get("/subscriptions")
            .then()
            .statusCode(200)
            .body("subscriptions", hasSize(2))
            .body("nextCursor", notNullValue());
    }

    @Test
    void getById_returns200() {
        // Given: stored subscription
        var template = new NotificationTemplate(
            "Title",
            null,
            NotificationSeverity.INFO,
            "category",
            null,
            "entity",
            "entityId",
            "actorId"
        );
        var input = new SubscriptionInput("user-1", TenancyConstants.DEFAULT_TENANT_ID, "Test subscription", "test.type", List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")), false, template, true, null
        );
        var subscription = store.store(input);

        // When: get by id
        given()
            .when()
            .get("/subscriptions/{id}", subscription.id())
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("id", equalTo(subscription.id()))
            .body("name", equalTo("Test subscription"))
            .body("ownerId", equalTo("user-1"));
    }

    @Test
    void getById_returns404ForDifferentUser() {
        // Given: subscription for different user
        var template = new NotificationTemplate(
            "Title",
            null,
            NotificationSeverity.INFO,
            "category",
            null,
            "entity",
            "entityId",
            "actorId"
        );
        var input = new SubscriptionInput("user-2", TenancyConstants.DEFAULT_TENANT_ID, "Test subscription", "test.type", List.of(), List.of(new NotificationTarget(TargetType.USER, "user-2")), false, template, true, null
        );
        var subscription = store.store(input);

        // When: try to get with current principal (different user)
        given()
            .when()
            .get("/subscriptions/{id}", subscription.id())
            .then()
            .statusCode(404);
    }

    @Test
    void update_changesNameAndReturns200() {
        // Given: stored subscription
        var template = new NotificationTemplate(
            "Title",
            null,
            NotificationSeverity.INFO,
            "category",
            null,
            "entity",
            "entityId",
            "actorId"
        );
        var input = new SubscriptionInput("user-1", TenancyConstants.DEFAULT_TENANT_ID, "Original name", "test.type", List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")), false, template, true, null
        );
        var subscription = store.store(input);

        // When: update name
        var update = new SubscriptionUpdate(
            "Updated name",
            null,
            null,
            null,
            null,
            null,
            null
        );

        given()
            .contentType(ContentType.JSON)
            .body(update)
            .when()
            .patch("/subscriptions/{id}", subscription.id())
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("id", equalTo(subscription.id()))
            .body("name", equalTo("Updated name"))
            .body("eventType", equalTo("test.type"));
    }

    @Test
    void update_returns404ForNonexistentSubscription() {
        var update = new SubscriptionUpdate(
            "Updated name",
            null,
            null,
            null,
            null,
            null,
            null
        );

        given()
            .contentType(ContentType.JSON)
            .body(update)
            .when()
            .patch("/subscriptions/{id}", "nonexistent-id")
            .then()
            .statusCode(404);
    }

    @Test
    void delete_returns204() {
        // Given: stored subscription
        var template = new NotificationTemplate(
            "Title",
            null,
            NotificationSeverity.INFO,
            "category",
            null,
            "entity",
            "entityId",
            "actorId"
        );
        var input = new SubscriptionInput("user-1", TenancyConstants.DEFAULT_TENANT_ID, "Test subscription", "test.type", List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")), false, template, true, null
        );
        var subscription = store.store(input);

        // When: delete
        given()
            .when()
            .delete("/subscriptions/{id}", subscription.id())
            .then()
            .statusCode(204);

        // Then: verify deleted
        given()
            .when()
            .get("/subscriptions/{id}", subscription.id())
            .then()
            .statusCode(404);
    }

    @Test
    void delete_returns404ForNonexistentSubscription() {
        given()
            .when()
            .delete("/subscriptions/{id}", "nonexistent-id")
            .then()
            .statusCode(404);
    }

    @Test
    void enable_setsEnabledTrue() {
        // Given: disabled subscription
        var template = new NotificationTemplate(
            "Title",
            null,
            NotificationSeverity.INFO,
            "category",
            null,
            "entity",
            "entityId",
            "actorId"
        );
        var input = new SubscriptionInput("user-1", TenancyConstants.DEFAULT_TENANT_ID, "Test subscription", "test.type", List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")), false, template, false, null
        );
        var subscription = store.store(input);

        // When: enable
        given()
            .when()
            .patch("/subscriptions/{id}/enable", subscription.id())
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("id", equalTo(subscription.id()))
            .body("enabled", equalTo(true));
    }

    @Test
    void disable_setsEnabledFalse() {
        // Given: enabled subscription
        var template = new NotificationTemplate(
            "Title",
            null,
            NotificationSeverity.INFO,
            "category",
            null,
            "entity",
            "entityId",
            "actorId"
        );
        var input = new SubscriptionInput("user-1", TenancyConstants.DEFAULT_TENANT_ID, "Test subscription", "test.type", List.of(), List.of(new NotificationTarget(TargetType.USER, "user-1")), false, template, true, null
        );
        var subscription = store.store(input);

        // When: disable
        given()
            .when()
            .patch("/subscriptions/{id}/disable", subscription.id())
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("id", equalTo(subscription.id()))
            .body("enabled", equalTo(false));
    }

// SYSTEM Scope Tests

    private NotificationTemplate createTestTemplate() {
        return new NotificationTemplate(
                "Title", null, NotificationSeverity.INFO, "category",
                null, "entity", "entityId", "actorId"
        );
    }

    private SubscriptionInput createSystemInput(String name, String eventType) {
        return new SubscriptionInput(
                principal.actorId(), principal.tenancyId(), name, eventType,
                List.of(), List.of(new NotificationTarget(TargetType.GROUP, "case-managers")),
                false, createTestTemplate(), true, SubscriptionScope.SYSTEM
        );
    }

    @Test
    void create_systemScope_adminSucceeds() {
        principal.setActorId("admin-1");
        principal.addGroup("subscription-admins");

        given()
                .contentType(ContentType.JSON)
                .body(createSystemInput("System Alert", "io.casehub.alert.triggered"))
                .when().post("/subscriptions")
                .then()
                .statusCode(201)
                .body("scope", equalTo("SYSTEM"))
                .body("ownerId", equalTo("admin-1"));
    }

    @Test
    void create_systemScope_nonAdminForbidden() {
        given()
                .contentType(ContentType.JSON)
                .body(createSystemInput("System Alert", "io.casehub.alert.triggered"))
                .when().post("/subscriptions")
                .then()
                .statusCode(403);
    }

    @Test
    void create_systemScope_emptyTargetsRejected() {
        principal.addGroup("subscription-admins");

        var input = new SubscriptionInput(
                "user-1", TenancyConstants.DEFAULT_TENANT_ID, "System Alert",
                "io.casehub.alert.triggered", List.of(),
                List.of(), false, createTestTemplate(), true, SubscriptionScope.SYSTEM
        );

        given()
                .contentType(ContentType.JSON)
                .body(input)
                .when().post("/subscriptions")
                .then()
                .statusCode(400);
    }

    @Test
    void create_systemScope_dollarMeConstraintRejected() {
        principal.addGroup("subscription-admins");

        var input = new SubscriptionInput(
                "user-1", TenancyConstants.DEFAULT_TENANT_ID, "System Alert",
                "io.casehub.alert.triggered",
                List.of((ExpressionEvaluator) new MvelExpressionEvaluator("assigneeId == $me")),
                List.of(new NotificationTarget(TargetType.GROUP, "case-managers")),
                false, createTestTemplate(), true, SubscriptionScope.SYSTEM
        );

        given()
                .contentType(ContentType.JSON)
                .body(input)
                .when().post("/subscriptions")
                .then()
                .statusCode(400);
    }

    @Test
    void list_systemScope_returnsTenantWide() {
        principal.setActorId("admin-1");
        principal.addGroup("subscription-admins");
        store.store(createSystemInput("System Alert", "io.casehub.alert.triggered"));

        principal.setActorId("user-1");
        principal.setGroups(Set.of());

        given()
                .queryParam("scope", "SYSTEM")
                .when().get("/subscriptions")
                .then()
                .statusCode(200)
                .body("subscriptions", hasSize(1))
                .body("subscriptions[0].scope", equalTo("SYSTEM"));
    }

    @Test
    void getById_systemScope_anyTenantUserCanRead() {
        principal.setActorId("admin-1");
        principal.addGroup("subscription-admins");
        var sub = store.store(createSystemInput("System Alert", "io.casehub.alert.triggered"));

        principal.setActorId("user-1");
        principal.setGroups(Set.of());

        given()
                .when().get("/subscriptions/{id}", sub.id())
                .then()
                .statusCode(200)
                .body("scope", equalTo("SYSTEM"));
    }

    @Test
    void update_systemScope_adminSucceeds() {
        principal.setActorId("admin-1");
        principal.addGroup("subscription-admins");
        var sub = store.store(createSystemInput("System Alert", "io.casehub.alert.triggered"));

        given()
                .contentType(ContentType.JSON)
                .body(new SubscriptionUpdate("Updated Alert", null, null, null, null, null, null))
                .when().patch("/subscriptions/{id}", sub.id())
                .then()
                .statusCode(200)
                .body("name", equalTo("Updated Alert"));
    }

    @Test
    void update_systemScope_nonAdminForbidden() {
        principal.setActorId("admin-1");
        principal.addGroup("subscription-admins");
        var sub = store.store(createSystemInput("System Alert", "io.casehub.alert.triggered"));

        principal.setActorId("user-1");
        principal.setGroups(Set.of());

        given()
                .contentType(ContentType.JSON)
                .body(new SubscriptionUpdate("Hacked", null, null, null, null, null, null))
                .when().patch("/subscriptions/{id}", sub.id())
                .then()
                .statusCode(403);
    }

    @Test
    void delete_systemScope_adminSucceeds() {
        principal.setActorId("admin-1");
        principal.addGroup("subscription-admins");
        var sub = store.store(createSystemInput("System Alert", "io.casehub.alert.triggered"));

        given()
                .when().delete("/subscriptions/{id}", sub.id())
                .then()
                .statusCode(204);
    }

    @Test
    void delete_systemScope_nonAdminForbidden() {
        principal.setActorId("admin-1");
        principal.addGroup("subscription-admins");
        var sub = store.store(createSystemInput("System Alert", "io.casehub.alert.triggered"));

        principal.setActorId("user-1");
        principal.setGroups(Set.of());

        given()
                .when().delete("/subscriptions/{id}", sub.id())
                .then()
                .statusCode(403);
    }

    @Test
    void enable_systemScope_nonAdminForbidden() {
        principal.setActorId("admin-1");
        principal.addGroup("subscription-admins");
        var sub = store.store(createSystemInput("System Alert", "io.casehub.alert.triggered"));

        principal.setActorId("user-1");
        principal.setGroups(Set.of());

        given()
                .when().patch("/subscriptions/{id}/enable", sub.id())
                .then()
                .statusCode(403);
    }

    @Test
    void create_userScope_backwardCompat() {
        var input = new SubscriptionInput(
                "user-1", TenancyConstants.DEFAULT_TENANT_ID, "My Sub",
                "io.casehub.work.item.created", List.of(),
                List.of(new NotificationTarget(TargetType.USER, "user-1")),
                false, createTestTemplate(), true, null
        );

        given()
                .contentType(ContentType.JSON)
                .body(input)
                .when().post("/subscriptions")
                .then()
                .statusCode(201)
                .body("scope", equalTo("USER"));
    }
}
