package io.casehub.platform.notification.settings;

import io.casehub.platform.api.util.UUIDv7;
import io.casehub.platform.api.notification.settings.MuteRule;
import io.casehub.platform.api.notification.settings.MuteRuleInput;
import io.casehub.platform.api.notification.settings.Snooze;
import io.casehub.platform.api.notification.settings.SnoozeInput;
import io.casehub.platform.api.notification.settings.SuppressionStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class NoOpSuppressionStore implements SuppressionStore {
    @Override public MuteRule addMute(final MuteRuleInput input) {
        return new MuteRule(UUIDv7.generate(), input.userId(), input.tenancyId(), input.scope(), input.scopeId(), input.entityType(), Instant.now(), input.expiresAt());
    }
    @Override public List<MuteRule> activeMutes(final String userId, final String tenancyId) { return List.of(); }
    @Override public boolean removeMute(final String muteId, final String userId, final String tenancyId) { return false; }
    @Override public Snooze activateSnooze(final SnoozeInput input) {
        return new Snooze(input.userId(), input.tenancyId(), input.until(), Instant.now());
    }
    @Override public Optional<Snooze> activeSnooze(final String userId, final String tenancyId) { return Optional.empty(); }
    @Override public boolean cancelSnooze(final String userId, final String tenancyId) { return false; }
}
