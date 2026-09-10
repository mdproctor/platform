package io.casehub.platform.notification.settings.inmem;

import io.casehub.platform.api.notification.settings.MuteRule;
import io.casehub.platform.api.notification.settings.MuteRuleInput;
import io.casehub.platform.api.notification.settings.MuteScope;
import io.casehub.platform.api.notification.settings.SnoozeInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemorySuppressionStoreTest {

    private InMemorySuppressionStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySuppressionStore();
    }

    // ===== Mute tests =====

    @Test
    void addMute_storesRule() {
        var input = new MuteRuleInput(
                "user1",
                "tenant1",
                MuteScope.ENTITY,
                "work-item-123",
                "work_item",
                null
        );

        var result = store.addMute(input);

        assertThat(result.id()).isNotBlank();
        assertThat(result.userId()).isEqualTo("user1");
        assertThat(result.tenancyId()).isEqualTo("tenant1");
        assertThat(result.scope()).isEqualTo(MuteScope.ENTITY);
        assertThat(result.scopeId()).isEqualTo("work-item-123");
        assertThat(result.entityType()).isEqualTo("work_item");
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.expiresAt()).isNull();
    }

    @Test
    void addMute_generatesUUIDv7Id() {
        var input = new MuteRuleInput(
                "user1",
                "tenant1",
                MuteScope.CATEGORY,
                "comments",
                null,
                null
        );

        var result1 = store.addMute(input);
        var result2 = store.addMute(input);

        assertThat(result1.id()).isNotEqualTo(result2.id());
        assertThat(result1.id()).hasSize(36); // UUID format
        assertThat(result2.id()).hasSize(36);
    }

    @Test
    void activeMutes_returnsOnlyForUser() {
        store.addMute(new MuteRuleInput("user1", "tenant1", MuteScope.CATEGORY, "comments", null, null));
        store.addMute(new MuteRuleInput("user2", "tenant1", MuteScope.CATEGORY, "updates", null, null));
        store.addMute(new MuteRuleInput("user1", "tenant2", MuteScope.CATEGORY, "mentions", null, null));

        var user1Tenant1 = store.activeMutes("user1", "tenant1");
        var user2Tenant1 = store.activeMutes("user2", "tenant1");
        var user1Tenant2 = store.activeMutes("user1", "tenant2");

        assertThat(user1Tenant1).hasSize(1);
        assertThat(user1Tenant1.get(0).scopeId()).isEqualTo("comments");

        assertThat(user2Tenant1).hasSize(1);
        assertThat(user2Tenant1.get(0).scopeId()).isEqualTo("updates");

        assertThat(user1Tenant2).hasSize(1);
        assertThat(user1Tenant2.get(0).scopeId()).isEqualTo("mentions");
    }

    @Test
    void activeMutes_filtersExpired_lazily() {
        var expired = new MuteRuleInput(
                "user1",
                "tenant1",
                MuteScope.CATEGORY,
                "comments",
                null,
                Instant.now().minus(1, ChronoUnit.HOURS)
        );
        var active = new MuteRuleInput(
                "user1",
                "tenant1",
                MuteScope.CATEGORY,
                "updates",
                null,
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        store.addMute(expired);
        store.addMute(active);

        var result = store.activeMutes("user1", "tenant1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).scopeId()).isEqualTo("updates");
    }

    @Test
    void removeMute_returnsTrue_whenExists() {
        var rule = store.addMute(new MuteRuleInput(
                "user1",
                "tenant1",
                MuteScope.CATEGORY,
                "comments",
                null,
                null
        ));

        var removed = store.removeMute(rule.id(), "user1", "tenant1");

        assertThat(removed).isTrue();
        assertThat(store.activeMutes("user1", "tenant1")).isEmpty();
    }

    @Test
    void removeMute_returnsFalse_whenNotFound() {
        var removed = store.removeMute("nonexistent-id", "user1", "tenant1");
        assertThat(removed).isFalse();
    }

    @Test
    void removeMute_enforcesUserOwnership() {
        var rule = store.addMute(new MuteRuleInput(
                "user1",
                "tenant1",
                MuteScope.CATEGORY,
                "comments",
                null,
                null
        ));

        // Different user cannot remove
        var removed = store.removeMute(rule.id(), "user2", "tenant1");
        assertThat(removed).isFalse();

        // Original user can remove
        removed = store.removeMute(rule.id(), "user1", "tenant1");
        assertThat(removed).isTrue();
    }

    @Test
    void mute_entity_scope_requiresEntityType() {
        var input = new MuteRuleInput(
                "user1",
                "tenant1",
                MuteScope.ENTITY,
                "work-item-123",
                null,  // entityType is null
                null
        );

        assertThatThrownBy(() -> store.addMute(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityType is required for ENTITY scope");
    }

    @Test
    void mute_category_scope_entityTypeOptional() {
        // No entityType (matches all entity types for the category)
        var input1 = new MuteRuleInput(
                "user1",
                "tenant1",
                MuteScope.CATEGORY,
                "comments",
                null,
                null
        );
        var result1 = store.addMute(input1);
        assertThat(result1.entityType()).isNull();

        // With entityType (matches only that entity type for the category)
        var input2 = new MuteRuleInput(
                "user1",
                "tenant1",
                MuteScope.CATEGORY,
                "comments",
                "work_item",
                null
        );
        var result2 = store.addMute(input2);
        assertThat(result2.entityType()).isEqualTo("work_item");
    }

    // ===== Snooze tests =====

    @Test
    void activateSnooze_storesSnooze() {
        var until = Instant.now().plus(2, ChronoUnit.HOURS);
        var input = new SnoozeInput("user1", "tenant1", until);

        var result = store.activateSnooze(input);

        assertThat(result.userId()).isEqualTo("user1");
        assertThat(result.tenancyId()).isEqualTo("tenant1");
        assertThat(result.until()).isEqualTo(until);
        assertThat(result.createdAt()).isNotNull();
    }

    @Test
    void activateSnooze_replacesExisting() {
        var until1 = Instant.now().plus(1, ChronoUnit.HOURS);
        var until2 = Instant.now().plus(2, ChronoUnit.HOURS);

        store.activateSnooze(new SnoozeInput("user1", "tenant1", until1));
        var result = store.activateSnooze(new SnoozeInput("user1", "tenant1", until2));

        assertThat(result.until()).isEqualTo(until2);

        var active = store.activeSnooze("user1", "tenant1");
        assertThat(active).isPresent();
        assertThat(active.get().until()).isEqualTo(until2);
    }

    @Test
    void activeSnooze_returnsEmpty_whenNone() {
        var result = store.activeSnooze("user1", "tenant1");
        assertThat(result).isEmpty();
    }

    @Test
    void activeSnooze_returnsEmpty_whenExpired() {
        var expired = Instant.now().minus(1, ChronoUnit.HOURS);
        store.activateSnooze(new SnoozeInput("user1", "tenant1", expired));

        var result = store.activeSnooze("user1", "tenant1");
        assertThat(result).isEmpty();
    }

    @Test
    void cancelSnooze_returnsTrue_whenActive() {
        var until = Instant.now().plus(1, ChronoUnit.HOURS);
        store.activateSnooze(new SnoozeInput("user1", "tenant1", until));

        var cancelled = store.cancelSnooze("user1", "tenant1");

        assertThat(cancelled).isTrue();
        assertThat(store.activeSnooze("user1", "tenant1")).isEmpty();
    }

    @Test
    void cancelSnooze_returnsFalse_whenNone() {
        var cancelled = store.cancelSnooze("user1", "tenant1");
        assertThat(cancelled).isFalse();
    }

    @Test
    void activeMutes_evictsExpiredRules() {
        store.addMute(new MuteRuleInput("user-1", "tenant-1", MuteScope.ENTITY,
                "entity-1", "work-item", Instant.now().minus(1, ChronoUnit.HOURS)));
        store.addMute(new MuteRuleInput("user-1", "tenant-1", MuteScope.ENTITY,
                "entity-2", "work-item", null));

        List<MuteRule> active = store.activeMutes("user-1", "tenant-1");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).scopeId()).isEqualTo("entity-2");

        // Second read should also return 1 — expired rule was evicted, not just filtered
        List<MuteRule> secondRead = store.activeMutes("user-1", "tenant-1");
        assertThat(secondRead).hasSize(1);
    }
}
