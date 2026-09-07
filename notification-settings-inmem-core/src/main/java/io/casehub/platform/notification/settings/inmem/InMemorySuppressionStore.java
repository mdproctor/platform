package io.casehub.platform.notification.settings.inmem;

import io.casehub.platform.api.util.UUIDv7;
import io.casehub.platform.api.notification.settings.MuteRule;
import io.casehub.platform.api.notification.settings.MuteRuleInput;
import io.casehub.platform.api.notification.settings.MuteScope;
import io.casehub.platform.api.notification.settings.Snooze;
import io.casehub.platform.api.notification.settings.SnoozeInput;
import io.casehub.platform.api.notification.settings.SuppressionStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySuppressionStore implements SuppressionStore {

    private final ConcurrentHashMap<String, List<MuteRule>> muteStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Snooze> snoozeStore = new ConcurrentHashMap<>();

    @Override
    public MuteRule addMute(MuteRuleInput input) {
        if (input.scope() == MuteScope.ENTITY && input.entityType() == null) {
            throw new IllegalArgumentException("entityType is required for ENTITY scope");
        }

        String key = makeKey(input.userId(), input.tenancyId());
        String id = UUIDv7.generate();
        Instant now = Instant.now();

        MuteRule rule = new MuteRule(
                id,
                input.userId(),
                input.tenancyId(),
                input.scope(),
                input.scopeId(),
                input.entityType(),
                now,
                input.expiresAt()
        );

        muteStore.compute(key, (k, rules) -> {
            List<MuteRule> updated = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
            updated.add(rule);
            return updated;
        });

        return rule;
    }

    @Override
    public List<MuteRule> activeMutes(String userId, String tenancyId) {
        String key = makeKey(userId, tenancyId);
        Instant now = Instant.now();
        List<MuteRule> active = new ArrayList<>();
        muteStore.computeIfPresent(key, (k, rules) -> {
            List<MuteRule> filtered = rules.stream()
                    .filter(r -> r.expiresAt() == null || !now.isAfter(r.expiresAt()))
                    .toList();
            active.addAll(filtered);
            return filtered.isEmpty() ? null : new ArrayList<>(filtered);
        });
        return active.isEmpty() ? List.of() : List.copyOf(active);
    }

    @Override
    public boolean removeMute(String muteId, String userId, String tenancyId) {
        String key = makeKey(userId, tenancyId);

        boolean[] removed = {false};
        muteStore.computeIfPresent(key, (k, rules) -> {
            List<MuteRule> updated = new ArrayList<>(rules);
            removed[0] = updated.removeIf(rule ->
                    rule.id().equals(muteId)
                            && rule.userId().equals(userId)
                            && rule.tenancyId().equals(tenancyId)
            );
            return updated.isEmpty() ? null : updated;
        });

        return removed[0];
    }

    @Override
    public Snooze activateSnooze(SnoozeInput input) {
        String key = makeKey(input.userId(), input.tenancyId());
        Instant now = Instant.now();

        Snooze snooze = new Snooze(
                input.userId(),
                input.tenancyId(),
                input.until(),
                now
        );

        snoozeStore.put(key, snooze);
        return snooze;
    }

    @Override
    public Optional<Snooze> activeSnooze(String userId, String tenancyId) {
        String key = makeKey(userId, tenancyId);
        Snooze snooze = snoozeStore.get(key);

        if (snooze == null) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        if (now.isAfter(snooze.until())) {
            return Optional.empty();
        }

        return Optional.of(snooze);
    }

    @Override
    public boolean cancelSnooze(String userId, String tenancyId) {
        String key = makeKey(userId, tenancyId);
        return snoozeStore.remove(key) != null;
    }

    private String makeKey(String userId, String tenancyId) {
        return userId + ":" + tenancyId;
    }

    public void clear() {
        muteStore.clear();
        snoozeStore.clear();
    }
}
