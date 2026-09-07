package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.identity.GroupMember;
import io.casehub.platform.api.identity.GroupMembershipProvider;
import io.casehub.platform.api.subscription.EntityWatcherProvider;
import io.casehub.platform.api.subscription.NotificationTarget;
import io.casehub.platform.api.subscription.Subscription;

import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.Set;

public class TargetResolver {

    private static final Logger LOG = Logger.getLogger(TargetResolver.class);

    private final GroupMembershipProvider groupMembershipProvider;
    private final EntityWatcherProvider entityWatcherProvider;

    public TargetResolver(final GroupMembershipProvider groupMembershipProvider,
                          final EntityWatcherProvider entityWatcherProvider) {
        this.groupMembershipProvider = groupMembershipProvider;
        this.entityWatcherProvider = entityWatcherProvider;
    }

    public Set<String> resolve(final Subscription subscription, final Object pojo) {
        final Set<String> recipients = new LinkedHashSet<>();

        for (final NotificationTarget target : subscription.targets()) {
            switch (target.type()) {
                case USER -> recipients.add(target.id());

                case GROUP -> {
                    final Set<GroupMember> members = groupMembershipProvider.membersOf(target.id(), subscription.tenancyId());
                    if (members.isEmpty()) {
                        LOG.warnf("GROUP target '%s' resolved to empty membership for subscription '%s'",
                                  target.id(), subscription.id());
                    }
                    for (final GroupMember member : members) {
                        recipients.add(member.actorId());
                    }
                }

                case EVENT_FIELD -> {
                    final String userId = TemplateResolver.extractField(pojo, target.id());
                    if (userId == null) {
                        LOG.warnf("EVENT_FIELD target '%s' resolved to null on %s for subscription '%s'",
                                  target.id(), pojo.getClass().getSimpleName(), subscription.id());
                    } else {
                        recipients.add(userId);
                    }
                }

                case ENTITY_WATCHERS -> {
                    final String entityType = target.id().isBlank()
                                              ? subscription.template().entityType()
                                              : target.id();
                    final String entityId = TemplateResolver.extractField(pojo,
                                                                          subscription.template().entityIdField());
                    if (entityId != null) {
                        final Set<String> watchers = entityWatcherProvider.watchersOf(
                                entityType, entityId, subscription.tenancyId());
                        if (watchers.isEmpty()) {
                            LOG.debugf("ENTITY_WATCHERS for %s/%s resolved to no watchers",
                                       entityType, entityId);
                        }
                        recipients.addAll(watchers);
                    } else {
                        LOG.warnf("ENTITY_WATCHERS target: entityIdField '%s' resolved to null on %s",
                                  subscription.template().entityIdField(), pojo.getClass().getSimpleName());
                    }
                }
            }
        }

        if (!subscription.includeActor()) {
            final String actorId = TemplateResolver.extractField(pojo,
                                                                 subscription.template().actorIdField());
            if (actorId != null) {
                recipients.remove(actorId);
            }
        }

        return recipients;
    }
}
