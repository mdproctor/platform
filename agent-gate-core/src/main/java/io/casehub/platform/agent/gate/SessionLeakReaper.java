package io.casehub.platform.agent.gate;

import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SessionLeakReaper {

    private static final Logger LOG = Logger.getLogger(SessionLeakReaper.class.getName());
    private static final Duration FORCE_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final SessionRegistry registry;
    private final Duration warnThreshold;
    private final boolean forceCloseEnabled;
    private final Duration forceCloseThreshold;
    private final Duration maxRegistryAge;

    public SessionLeakReaper(SessionRegistry registry,
                      Duration warnThreshold,
                      boolean forceCloseEnabled,
                      Duration forceCloseThreshold,
                      Duration maxRegistryAge) {
        this.registry = registry;
        this.warnThreshold = warnThreshold;
        this.forceCloseEnabled = forceCloseEnabled;
        this.forceCloseThreshold = forceCloseThreshold;
        this.maxRegistryAge = maxRegistryAge;
        validateThresholds();
    }

    private void validateThresholds() {
        if (warnThreshold.compareTo(forceCloseThreshold) > 0) {
            throw new IllegalArgumentException(
                "reaper.warn-threshold (" + warnThreshold + ") must be <= force-close-threshold (" + forceCloseThreshold + ")");
        }
        if (forceCloseThreshold.compareTo(maxRegistryAge) > 0) {
            throw new IllegalArgumentException(
                "reaper.force-close-threshold (" + forceCloseThreshold + ") must be <= max-registry-age (" + maxRegistryAge + ")");
        }
    }

    public void scan() {
        var snapshot = registry.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }

        var now = Instant.now();
        for (var entry : snapshot.values()) {
            Duration held = Duration.between(entry.createdAt(), now);

            if (held.compareTo(maxRegistryAge) > 0) {
                try {
                    entry.session().close(FORCE_CLOSE_TIMEOUT);
                    LOG.warning(String.format(
                        "Evicted stale GatedAgentSession: sessionId=%d, held for %s — closed and semaphore recovered",
                        entry.id(), held));
                } catch (Exception e) {
                    registry.deregister(entry.id());
                    LOG.log(Level.WARNING, String.format(
                        "Evicted stale GatedAgentSession: sessionId=%d, held for %s — close failed, semaphore slot permanently leaked",
                        entry.id(), held), e);
                }
                continue;
            }

            if (forceCloseEnabled && held.compareTo(forceCloseThreshold) > 0) {
                try {
                    entry.session().close(FORCE_CLOSE_TIMEOUT);
                    LOG.warning(String.format(
                        "Force-closed leaked GatedAgentSession: sessionId=%d, held for %s, created at %s",
                        entry.id(), held, entry.createdAt()));
                } catch (Exception e) {
                    LOG.log(Level.WARNING, String.format(
                        "Failed to force-close leaked GatedAgentSession: sessionId=%d, held for %s",
                        entry.id(), held), e);
                }
                continue;
            }

            if (held.compareTo(warnThreshold) > 0) {
                LOG.warning(String.format(
                    "Leaked GatedAgentSession detected: sessionId=%d, held for %s, created at %s",
                    entry.id(), held, entry.createdAt()));
            }
        }
    }
}
