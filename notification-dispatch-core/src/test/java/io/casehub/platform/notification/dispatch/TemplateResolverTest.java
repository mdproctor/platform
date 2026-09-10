package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.notification.NotificationSeverity;
import io.casehub.platform.api.subscription.NotificationTemplate;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateResolverTest {

    @Test
    void resolve_substitutesPlaceholders() {
        var template = new NotificationTemplate(
                "WorkItem {status}: {detail}", null,
                NotificationSeverity.INFO, "work-item.completed",
                "/workitems/{workItemId}", "work-item", "workItemId", "actor");
        var pojo = new TestWorkItem("completed", "Fix login bug",
                UUID.randomUUID(), "user-2");
        var input = TemplateResolver.resolve(template, pojo, "sub-user", "tenant-1");

        assertThat(input).isNotNull();
        assertThat(input.title()).isEqualTo("WorkItem completed: Fix login bug");
        assertThat(input.userId()).isEqualTo("sub-user");
        assertThat(input.tenancyId()).isEqualTo("tenant-1");
        assertThat(input.category()).isEqualTo("work-item.completed");
        assertThat(input.severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(input.actionUrl()).isEqualTo("/workitems/" + pojo.workItemId());
        assertThat(input.source().entityType()).isEqualTo("work-item");
        assertThat(input.source().entityId()).isEqualTo(pojo.workItemId().toString());
        assertThat(input.source().actorId()).isEqualTo("user-2");
        assertThat(input.source().eventId()).isNotBlank();
    }

    @Test
    void resolve_bodyPattern_substituted() {
        var template = new NotificationTemplate(
                "Title", "Body with {status}",
                NotificationSeverity.WARNING, "test.category",
                null, "test-entity", "workItemId", "actor");
        var pojo = new TestWorkItem("active", "detail", UUID.randomUUID(), "actor-1");
        var input = TemplateResolver.resolve(template, pojo, "user-1", "tenant-1");

        assertThat(input).isNotNull();
        assertThat(input.body()).isEqualTo("Body with active");
    }

    @Test
    void resolve_nullBodyPattern_yieldsNullBody() {
        var template = new NotificationTemplate(
                "Title", null,
                NotificationSeverity.INFO, "test.category",
                null, "test-entity", "workItemId", "actor");
        var pojo = new TestWorkItem("done", "detail", UUID.randomUUID(), "actor-1");
        var input = TemplateResolver.resolve(template, pojo, "user-1", "tenant-1");

        assertThat(input).isNotNull();
        assertThat(input.body()).isNull();
    }

    @Test
    void resolve_nullActionUrlPattern_yieldsNullActionUrl() {
        var template = new NotificationTemplate(
                "Title", null,
                NotificationSeverity.INFO, "test.category",
                null, "test-entity", "workItemId", "actor");
        var pojo = new TestWorkItem("done", "detail", UUID.randomUUID(), "actor-1");
        var input = TemplateResolver.resolve(template, pojo, "user-1", "tenant-1");

        assertThat(input).isNotNull();
        assertThat(input.actionUrl()).isNull();
    }

    @Test
    void resolve_returnsNullWhenEntityIdFieldResolvesToNull() {
        var template = new NotificationTemplate(
                "Title", null,
                NotificationSeverity.INFO, "test.category",
                null, "test-entity", "missingField", "actor");
        var pojo = new TestWorkItem("done", "detail", UUID.randomUUID(), "actor-1");
        var input = TemplateResolver.resolve(template, pojo, "user-1", "tenant-1");

        assertThat(input).isNull();
    }

    @Test
    void resolve_returnsNullWhenActorIdFieldResolvesToNull() {
        var template = new NotificationTemplate(
                "Title", null,
                NotificationSeverity.INFO, "test.category",
                null, "test-entity", "workItemId", "missingField");
        var pojo = new TestWorkItem("done", "detail", UUID.randomUUID(), "actor-1");
        var input = TemplateResolver.resolve(template, pojo, "user-1", "tenant-1");

        assertThat(input).isNull();
    }

    @Test
    void resolve_placeholderNotFoundOnPojo_leftAsIs() {
        var template = new NotificationTemplate(
                "Title {unknownField}", null,
                NotificationSeverity.INFO, "test.category",
                null, "test-entity", "workItemId", "actor");
        var pojo = new TestWorkItem("done", "detail", UUID.randomUUID(), "actor-1");
        var input = TemplateResolver.resolve(template, pojo, "user-1", "tenant-1");

        assertThat(input).isNotNull();
        assertThat(input.title()).isEqualTo("Title {unknownField}");
    }

    @Test
    void resolve_eventIdIsUUIDv7() {
        var template = new NotificationTemplate(
                "Title", null,
                NotificationSeverity.INFO, "test.category",
                null, "test-entity", "workItemId", "actor");
        var pojo = new TestWorkItem("done", "detail", UUID.randomUUID(), "actor-1");
        var input = TemplateResolver.resolve(template, pojo, "user-1", "tenant-1");

        assertThat(input).isNotNull();
        // UUIDv7 has version nibble 7 — character at position 14 is '7'
        assertThat(input.source().eventId().charAt(14)).isEqualTo('7');
    }

    @Test
    void resolve_rejectsNullTemplate() {
        var pojo = new TestWorkItem("done", "detail", UUID.randomUUID(), "actor-1");
        assertThatThrownBy(() -> TemplateResolver.resolve(null, pojo, "user-1", "tenant-1"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void resolve_rejectsNullPojo() {
        var template = new NotificationTemplate(
                "Title", null,
                NotificationSeverity.INFO, "test.category",
                null, "test-entity", "workItemId", "actor");
        assertThatThrownBy(() -> TemplateResolver.resolve(template, null, "user-1", "tenant-1"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void extractField_cachedAcrossCalls() {
        var event = new TestEvent("entity-1", "actor-1");
        // First call populates cache
        String first = TemplateResolver.extractField(event, "entityId");
        // Second call uses cache
        String second = TemplateResolver.extractField(event, "entityId");
        assertThat(first).isEqualTo("entity-1");
        assertThat(second).isEqualTo("entity-1");
    }

    @Test
    void extractField_missingField_cachedAsEmpty() {
        var event = new TestEvent("entity-1", "actor-1");
        // First call — field doesn't exist
        String first = TemplateResolver.extractField(event, "nonExistent");
        // Second call — should return null without re-reflecting
        String second = TemplateResolver.extractField(event, "nonExistent");
        assertThat(first).isNull();
        assertThat(second).isNull();
    }

    record TestWorkItem(String status, String detail, UUID workItemId, String actor) {}

    record TestEvent(String entityId, String actorId) {}
}
