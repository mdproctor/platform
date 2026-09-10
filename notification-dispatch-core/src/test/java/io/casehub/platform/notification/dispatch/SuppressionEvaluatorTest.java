package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.notification.settings.MuteRule;
import io.casehub.platform.api.notification.settings.MuteScope;
import io.casehub.platform.api.notification.settings.QuietHours;
import io.casehub.platform.api.notification.settings.Snooze;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SuppressionEvaluatorTest {

    private final SuppressionEvaluator evaluator = new SuppressionEvaluator();

    private static final String USER = "user-1";
    private static final String TENANT = "tenant-1";
    private static final Instant NOW = Instant.now();
    private static final ZoneId TZ = ZoneId.of("UTC");
    // For "within quiet hours 22:00-07:00 at 23:00 UTC"
    private static final Instant DURING_QH = LocalDate.of(2026, 1, 1).atTime(23, 0).atZone(TZ).toInstant();
    // For "outside quiet hours at 12:00 UTC"
    private static final Instant OUTSIDE_QH = LocalDate.of(2026, 1, 1).atTime(12, 0).atZone(TZ).toInstant();

    @Test
    void evaluate_noMutesNoSnooze_returnsAllFalse() {
        var result = evaluator.evaluate(
                List.of(), Optional.empty(), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.isMuted()).isFalse();
        assertThat(result.isSnoozed()).isFalse();
        assertThat(result.quietHoursActive()).isFalse();
    }

    @Test
    void evaluate_entityMuteMatches_isMutedTrue() {
        var mute = new MuteRule("m-1", USER, TENANT, MuteScope.ENTITY,
                "wi-123", "work-item", NOW, null);

        var result = evaluator.evaluate(
                List.of(mute), Optional.empty(), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.isMuted()).isTrue();
    }

    @Test
    void evaluate_entityMute_differentEntityId_notMuted() {
        var mute = new MuteRule("m-1", USER, TENANT, MuteScope.ENTITY,
                "wi-999", "work-item", NOW, null);

        var result = evaluator.evaluate(
                List.of(mute), Optional.empty(), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.isMuted()).isFalse();
    }

    @Test
    void evaluate_entityMute_differentEntityType_notMuted() {
        var mute = new MuteRule("m-1", USER, TENANT, MuteScope.ENTITY,
                "wi-123", "case", NOW, null);

        var result = evaluator.evaluate(
                List.of(mute), Optional.empty(), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.isMuted()).isFalse();
    }

    @Test
    void evaluate_categoryMuteMatches_isMutedTrue() {
        var mute = new MuteRule("m-1", USER, TENANT, MuteScope.CATEGORY,
                "comment", null, NOW, null);

        var result = evaluator.evaluate(
                List.of(mute), Optional.empty(), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.isMuted()).isTrue();
    }

    @Test
    void evaluate_categoryMuteWithEntityType_matchesBoth() {
        var mute = new MuteRule("m-1", USER, TENANT, MuteScope.CATEGORY,
                "comment", "work-item", NOW, null);

        var result = evaluator.evaluate(
                List.of(mute), Optional.empty(), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.isMuted()).isTrue();
    }

    @Test
    void evaluate_categoryMuteWithEntityType_noMatchOnDifferentEntity() {
        var mute = new MuteRule("m-1", USER, TENANT, MuteScope.CATEGORY,
                "comment", "case", NOW, null);

        var result = evaluator.evaluate(
                List.of(mute), Optional.empty(), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.isMuted()).isFalse();
    }

    @Test
    void evaluate_expiredMuteFromStore_notRefiltered() {
        // Store returns an "expired" rule — evaluator should trust the store and treat it as active.
        // This verifies the evaluator no longer re-filters by expiry.
        var expiredRule = new MuteRule("rule-1", USER, TENANT, MuteScope.ENTITY,
                "entity-1", "work-item", NOW.minus(2, ChronoUnit.HOURS),
                NOW.minus(1, ChronoUnit.HOURS));
        var result = evaluator.evaluate(List.of(expiredRule), Optional.empty(), null,
                "work-item", "entity-1", "test-category", NOW);
        // Store is authoritative — if it returned the rule, the evaluator trusts it
        assertThat(result.isMuted()).isTrue();
    }

    @Test
    void evaluate_activeSnooze_isSnoozedTrue() {
        var snooze = new Snooze(USER, TENANT,
                NOW.plus(1, ChronoUnit.HOURS), NOW);

        var result = evaluator.evaluate(
                List.of(), Optional.of(snooze), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.isSnoozed()).isTrue();
    }

    @Test
    void evaluate_expiredSnooze_isSnoozedFalse() {
        var snooze = new Snooze(USER, TENANT,
                NOW.minus(1, ChronoUnit.HOURS), NOW.minus(2, ChronoUnit.HOURS));

        var result = evaluator.evaluate(
                List.of(), Optional.of(snooze), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.isSnoozed()).isFalse();
    }

    @Test
    void evaluate_quietHoursActive_sameDayWindow() {
        // Test at 12:00 UTC with window 10:00-14:00
        var testTime = OUTSIDE_QH; // 12:00 UTC
        var quietHours = new QuietHours(LocalTime.of(10, 0), LocalTime.of(14, 0), TZ, null);

        var result = evaluator.evaluate(
                List.of(), Optional.empty(), quietHours,
                "work-item", "wi-123", "comment", testTime);

        assertThat(result.quietHoursActive()).isTrue();
    }

    @Test
    void evaluate_quietHoursActive_crossMidnight() {
        // Cross-midnight: 22:00 to 07:00 — test at 23:00 UTC
        var testTime = DURING_QH; // 23:00 UTC
        var quietHours = new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), TZ, null);

        var result = evaluator.evaluate(
                List.of(), Optional.empty(), quietHours,
                "work-item", "wi-123", "comment", testTime);

        assertThat(result.quietHoursActive()).isTrue();
    }

    @Test
    void evaluate_quietHoursInactive_outsideWindow() {
        // Test at 12:00 UTC with window 14:00-16:00 (outside)
        var testTime = OUTSIDE_QH; // 12:00 UTC
        var quietHours = new QuietHours(LocalTime.of(14, 0), LocalTime.of(16, 0), TZ, null);

        var result = evaluator.evaluate(
                List.of(), Optional.empty(), quietHours,
                "work-item", "wi-123", "comment", testTime);

        assertThat(result.quietHoursActive()).isFalse();
    }

    @Test
    void evaluate_noQuietHours_quietHoursActiveFalse() {
        var result = evaluator.evaluate(
                List.of(), Optional.empty(), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.quietHoursActive()).isFalse();
    }

    @Test
    void evaluate_multipleMutes_firstMatchWins() {
        var mute1 = new MuteRule("m-1", USER, TENANT, MuteScope.ENTITY,
                "wi-999", "work-item", NOW, null); // no match
        var mute2 = new MuteRule("m-2", USER, TENANT, MuteScope.CATEGORY,
                "comment", null, NOW, null); // match

        var result = evaluator.evaluate(
                List.of(mute1, mute2), Optional.empty(), null,
                "work-item", "wi-123", "comment", NOW);

        assertThat(result.isMuted()).isTrue();
    }

    @Test
    void evaluate_allThreeActive() {
        var testTime = OUTSIDE_QH; // 12:00 UTC
        var mute = new MuteRule("m-1", USER, TENANT, MuteScope.ENTITY,
                "wi-123", "work-item", testTime, null);
        var snooze = new Snooze(USER, TENANT,
                testTime.plus(1, ChronoUnit.HOURS), testTime);
        var quietHours = new QuietHours(
                LocalTime.of(10, 0), LocalTime.of(14, 0), TZ, null);

        var result = evaluator.evaluate(
                List.of(mute), Optional.of(snooze), quietHours,
                "work-item", "wi-123", "comment", testTime);

        assertThat(result.isMuted()).isTrue();
        assertThat(result.isSnoozed()).isTrue();
        assertThat(result.quietHoursActive()).isTrue();
    }

    @Test
    void evaluateUserLevel_noSnoozeNoQuietHours_allFalse() {
        var result = evaluator.evaluateUserLevel(Optional.empty(), null, NOW);

        assertThat(result.isMuted()).isFalse();
        assertThat(result.isSnoozed()).isFalse();
        assertThat(result.quietHoursActive()).isFalse();
    }

    @Test
    void evaluateUserLevel_activeSnooze_snoozedTrue() {
        var snooze = new Snooze(USER, TENANT, NOW.plus(1, ChronoUnit.HOURS), NOW);

        var result = evaluator.evaluateUserLevel(Optional.of(snooze), null, NOW);

        assertThat(result.isMuted()).isFalse();
        assertThat(result.isSnoozed()).isTrue();
    }

    @Test
    void evaluateUserLevel_quietHoursActive_quietHoursTrue() {
        var testTime = OUTSIDE_QH; // 12:00 UTC
        var quietHours = new QuietHours(LocalTime.of(10, 0), LocalTime.of(14, 0), TZ, null);

        var result = evaluator.evaluateUserLevel(Optional.empty(), quietHours, testTime);

        assertThat(result.isMuted()).isFalse();
        assertThat(result.quietHoursActive()).isTrue();
    }

    @Test
    void evaluateUserLevel_neverReturnsMuted() {
        var testTime = OUTSIDE_QH; // 12:00 UTC
        var snooze = new Snooze(USER, TENANT, testTime.plus(1, ChronoUnit.HOURS), testTime);
        var quietHours = new QuietHours(LocalTime.of(10, 0), LocalTime.of(14, 0), TZ, null);

        var result = evaluator.evaluateUserLevel(Optional.of(snooze), quietHours, testTime);

        assertThat(result.isMuted()).isFalse();
        assertThat(result.isSnoozed()).isTrue();
        assertThat(result.quietHoursActive()).isTrue();
    }
}
