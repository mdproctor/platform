package io.casehub.platform.subscription;

import io.casehub.platform.api.subscription.EntityWatcherProvider;

import java.util.Set;
import java.util.logging.Logger;

public class NoOpEntityWatcherProvider implements EntityWatcherProvider {
    private static final Logger LOG = Logger.getLogger(NoOpEntityWatcherProvider.class.getName());

    @Override
    public Set<String> watchersOf(final String entityType, final String entityId, final String tenancyId) {
        LOG.warning("ENTITY_WATCHERS target used but no EntityWatcherProvider is registered — notifications to " + entityType + "/" + entityId + " will not be delivered");
        return Set.of();
    }
}
