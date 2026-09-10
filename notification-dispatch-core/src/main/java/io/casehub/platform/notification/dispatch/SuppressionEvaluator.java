package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.notification.settings.MuteRule;
import io.casehub.platform.api.notification.settings.MuteScope;
import io.casehub.platform.api.notification.settings.QuietHours;
import io.casehub.platform.api.notification.settings.Snooze;
import io.casehub.platform.api.notification.settings.SuppressionResult;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public class SuppressionEvaluator {

    public SuppressionResult evaluate(final List<MuteRule> activeMutes,
                                      final Optional<Snooze> activeSnooze,
                                      final QuietHours quietHours,
                                      final String entityType,
                                      final String entityId,
                                      final String category,
                                      final Instant now) {
        final boolean isMuted = checkMuted(activeMutes, entityType, entityId, category);
        final boolean isSnoozed = checkSnoozed(activeSnooze, now);
        final boolean quietHoursActive = checkQuietHours(quietHours, now);

        return new SuppressionResult(isMuted, isSnoozed, quietHoursActive);
    }

    public SuppressionResult evaluateUserLevel(final Optional<Snooze> activeSnooze,
                                               final QuietHours quietHours,
                                               final Instant now) {
        return new SuppressionResult(false, checkSnoozed(activeSnooze, now), checkQuietHours(quietHours, now));
    }

    private boolean checkMuted(final List<MuteRule> activeMutes,
                               final String entityType,
                               final String entityId,
                               final String category) {
        for (final MuteRule rule : activeMutes) {
            if (rule.scope() == MuteScope.ENTITY) {
                if (entityType.equals(rule.entityType()) && entityId.equals(rule.scopeId())) {
                    return true;
                }
            } else if (rule.scope() == MuteScope.CATEGORY) {
                if (category.equals(rule.scopeId())) {
                    if (rule.entityType() == null || rule.entityType().equals(entityType)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean checkSnoozed(final Optional<Snooze> activeSnooze, final Instant now) {
        return activeSnooze
                .filter(snooze -> now.isBefore(snooze.until()))
                .isPresent();
    }

    boolean checkQuietHours(final QuietHours quietHours, final Instant now) {
        if (quietHours == null) {
            return false;
        }

        final LocalTime localNow = now.atZone(quietHours.timezone()).toLocalTime();
        final LocalTime start = quietHours.start();
        final LocalTime end = quietHours.end();

        if (start.isBefore(end)) {
            return !localNow.isBefore(start) && localNow.isBefore(end);
        } else {
            return !localNow.isBefore(start) || localNow.isBefore(end);
        }
    }
}
