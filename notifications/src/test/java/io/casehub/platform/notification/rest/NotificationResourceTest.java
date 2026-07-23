package io.casehub.platform.notification.rest;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.notification.Notification;
import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.notification.NotificationStatus;
import io.casehub.platform.api.notification.NotificationStore;
import io.casehub.platform.notification.inmem.InMemoryNotificationStore;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class NotificationResourceTest {

    @Inject
    FixedCurrentPrincipal principal;

    @Inject
    NotificationStore store;

    @Inject
    InMemoryNotificationStore inMemoryStore;

    @BeforeEach
    void setUp() {
        principal.reset();
        principal.setActorId("user-1");
        principal.setTenancyId(TenancyConstants.DEFAULT_TENANT_ID);
        inMemoryStore.clear(); // Clear state between tests
    }

    @Test
    void list_returnsNotificationsForCurrentUser() {
        // Given: notification for user-1
        var source = new NotificationSource("evt-1", "work-item", "wi-1", "other-user");
        var input = new NotificationInput(
            "user-1",
            TenancyConstants.DEFAULT_TENANT_ID,
            "Test notification",
            "Body text",
            "work-item.created",
            NotificationSeverity.INFO,
            "/work-items/wi-1",
            source
        );
        store.store(input);

        // When: list notifications
        given()
            .when()
            .get("/notifications")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("notifications", hasSize(1))
            .body("notifications[0].title", equalTo("Test notification"))
            .body("notifications[0].userId", equalTo("user-1"))
            .body("notifications[0].status", equalTo("UNREAD"))
            .body("nextCursor", nullValue());
    }

    @Test
    void list_filtersUnreadNotifications() {
        // Given: one unread, one read notification
        var source = new NotificationSource("evt-1", "work-item", "wi-1", "other-user");
        var input1 = new NotificationInput(
            "user-1",
            TenancyConstants.DEFAULT_TENANT_ID,
            "Unread notification",
            null,
            "work-item.created",
            NotificationSeverity.INFO,
            null,
            source
        );
        var n1 = store.store(input1);

        var input2 = new NotificationInput(
            "user-1",
            TenancyConstants.DEFAULT_TENANT_ID,
            "Read notification",
            null,
            "work-item.updated",
            NotificationSeverity.INFO,
            null,
            source
        );
        var n2 = store.store(input2);
        store.markRead(n2.id(), "user-1", TenancyConstants.DEFAULT_TENANT_ID);

        // When: list with status=UNREAD filter
        given()
            .queryParam("status", "UNREAD")
            .when()
            .get("/notifications")
            .then()
            .statusCode(200)
            .body("notifications", hasSize(1))
            .body("notifications[0].title", equalTo("Unread notification"));
    }

    @Test
    void list_filtersByCategory() {
        // Given: notifications with different categories
        var source = new NotificationSource("evt-1", "work-item", "wi-1", "other-user");
        var input1 = new NotificationInput(
            "user-1",
            TenancyConstants.DEFAULT_TENANT_ID,
            "Work item created",
            null,
            "work-item.created",
            NotificationSeverity.INFO,
            null,
            source
        );
        store.store(input1);

        var input2 = new NotificationInput(
            "user-1",
            TenancyConstants.DEFAULT_TENANT_ID,
            "SLA breached",
            null,
            "sla.breached",
            NotificationSeverity.URGENT,
            null,
            source
        );
        store.store(input2);

        // When: list with category filter
        given()
            .queryParam("category", "sla.breached")
            .when()
            .get("/notifications")
            .then()
            .statusCode(200)
            .body("notifications", hasSize(1))
            .body("notifications[0].title", equalTo("SLA breached"));
    }

    @Test
    void list_respectsPaginationLimit() {
        // Given: 3 notifications
        var source = new NotificationSource("evt-1", "work-item", "wi-1", "other-user");
        for (int i = 1; i <= 3; i++) {
            var input = new NotificationInput(
                "user-1",
                TenancyConstants.DEFAULT_TENANT_ID,
                "Notification " + i,
                null,
                "work-item.created",
                NotificationSeverity.INFO,
                null,
                source
            );
            store.store(input);
        }

        // When: list with limit=2
        given()
            .queryParam("limit", 2)
            .when()
            .get("/notifications")
            .then()
            .statusCode(200)
            .body("notifications", hasSize(2))
            .body("nextCursor", notNullValue());
    }

    @Test
    void unreadCount_returnsCount() {
        // Given: 2 unread notifications
        var source = new NotificationSource("evt-1", "work-item", "wi-1", "other-user");
        for (int i = 1; i <= 2; i++) {
            var input = new NotificationInput(
                "user-1",
                TenancyConstants.DEFAULT_TENANT_ID,
                "Notification " + i,
                null,
                "work-item.created",
                NotificationSeverity.INFO,
                null,
                source
            );
            store.store(input);
        }

        // When: get unread count
        given()
            .when()
            .get("/notifications/unread-count")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("count", equalTo(2));
    }

    @Test
    void markRead_returns200WithUpdatedNotification() {
        // Given: unread notification
        var source = new NotificationSource("evt-1", "work-item", "wi-1", "other-user");
        var input = new NotificationInput(
            "user-1",
            TenancyConstants.DEFAULT_TENANT_ID,
            "Test notification",
            null,
            "work-item.created",
            NotificationSeverity.INFO,
            null,
            source
        );
        var notification = store.store(input);

        // When: mark as read
        given()
            .when()
            .patch("/notifications/{id}/read", notification.id())
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("id", equalTo(notification.id()))
            .body("status", equalTo("READ"))
            .body("readAt", notNullValue());
    }

    @Test
    void markRead_returns404ForNonexistentNotification() {
        given()
            .when()
            .patch("/notifications/{id}/read", "nonexistent-id")
            .then()
            .statusCode(404);
    }

    @Test
    void markRead_returns404ForDifferentTenant() {
        // Given: notification for different tenant
        var source = new NotificationSource("evt-1", "work-item", "wi-1", "other-user");
        var input = new NotificationInput(
            "user-1",
            "other-tenant-id",
            "Test notification",
            null,
            "work-item.created",
            NotificationSeverity.INFO,
            null,
            source
        );
        var notification = store.store(input);

        // When: try to mark as read with current principal (different tenant)
        given()
            .when()
            .patch("/notifications/{id}/read", notification.id())
            .then()
            .statusCode(404);
    }

    @Test
    void markRead_returns404ForDifferentUser() {
        // Given: notification for different user
        var source = new NotificationSource("evt-1", "work-item", "wi-1", "other-user");
        var input = new NotificationInput(
            "user-2",
            TenancyConstants.DEFAULT_TENANT_ID,
            "Test notification",
            null,
            "work-item.created",
            NotificationSeverity.INFO,
            null,
            source
        );
        var notification = store.store(input);

        // When: try to mark as read with current principal (different user)
        given()
            .when()
            .patch("/notifications/{id}/read", notification.id())
            .then()
            .statusCode(404);
    }

    @Test
    void dismiss_returns200WithUpdatedNotification() {
        // Given: unread notification
        var source = new NotificationSource("evt-1", "work-item", "wi-1", "other-user");
        var input = new NotificationInput(
            "user-1",
            TenancyConstants.DEFAULT_TENANT_ID,
            "Test notification",
            null,
            "work-item.created",
            NotificationSeverity.INFO,
            null,
            source
        );
        var notification = store.store(input);

        // When: dismiss
        given()
            .when()
            .patch("/notifications/{id}/dismiss", notification.id())
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("id", equalTo(notification.id()))
            .body("status", equalTo("DISMISSED"))
            .body("dismissedAt", notNullValue());
    }

    @Test
    void dismiss_returns404ForNonexistentNotification() {
        given()
            .when()
            .patch("/notifications/{id}/dismiss", "nonexistent-id")
            .then()
            .statusCode(404);
    }

    @Test
    void markAllRead_returns200WithCount() {
        // Given: 3 unread notifications
        var source = new NotificationSource("evt-1", "work-item", "wi-1", "other-user");
        for (int i = 1; i <= 3; i++) {
            var input = new NotificationInput(
                "user-1",
                TenancyConstants.DEFAULT_TENANT_ID,
                "Notification " + i,
                null,
                "work-item.created",
                NotificationSeverity.INFO,
                null,
                source
            );
            store.store(input);
        }

        // When: mark all as read
        given()
            .when()
            .post("/notifications/mark-all-read")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("count", equalTo(3));
    }

    @Test
    void markAllRead_returnsZeroWhenNoUnreadNotifications() {
        // When: mark all as read with no notifications
        given()
            .when()
            .post("/notifications/mark-all-read")
            .then()
            .statusCode(200)
            .body("count", equalTo(0));
    }
}
