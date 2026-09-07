package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.notification.NotificationInput;
import io.casehub.platform.api.notification.NotificationSource;
import io.casehub.platform.api.util.UUIDv7;
import io.casehub.platform.api.subscription.NotificationTemplate;

import java.lang.invoke.MethodHandles;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

public final class TemplateResolver {

    private static final Logger LOG = Logger.getLogger(TemplateResolver.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Optional<java.lang.invoke.MethodHandle>>> HANDLE_CACHE =
            new ConcurrentHashMap<>();

    private TemplateResolver() {}

    public static NotificationInput resolve(final NotificationTemplate template,
                                            final Object pojo,
                                            final String userId,
                                            final String tenancyId) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(pojo, "pojo");

        final String entityId = extractField(pojo, template.entityIdField());
        if (entityId == null) {
            LOG.warnf("entityIdField '%s' resolved to null on %s — skipping notification",
                    template.entityIdField(), pojo.getClass().getSimpleName());
            return null;
        }

        final String actorId = extractField(pojo, template.actorIdField());
        if (actorId == null) {
            LOG.warnf("actorIdField '%s' resolved to null on %s — skipping notification",
                    template.actorIdField(), pojo.getClass().getSimpleName());
            return null;
        }

        final String title = substitutePlaceholders(template.titlePattern(), pojo);
        final String body = template.bodyPattern() != null
                ? substitutePlaceholders(template.bodyPattern(), pojo)
                : null;
        final String actionUrl = template.actionUrlPattern() != null
                ? substitutePlaceholders(template.actionUrlPattern(), pojo)
                : null;

        final var source = new NotificationSource(
                UUIDv7.generate(),
                template.entityType(),
                entityId,
                actorId);

        return new NotificationInput(
                userId,
                tenancyId,
                title,
                body,
                template.category(),
                template.severity(),
                actionUrl,
                source);
    }

    public static String extractField(final Object pojo, final String fieldName) {
        try {
            var classHandles = HANDLE_CACHE.computeIfAbsent(pojo.getClass(), k -> new ConcurrentHashMap<>());
            var handle = classHandles.computeIfAbsent(fieldName, f -> {
                try {
                    var method = pojo.getClass().getMethod(f);
                    return Optional.of(MethodHandles.lookup().unreflect(method));
                } catch (Exception e) {
                    return Optional.empty();
                }
            });
            if (handle.isEmpty()) return null;
            final Object value = handle.get().invoke(pojo);
            return value != null ? value.toString() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String substitutePlaceholders(final String pattern, final Object pojo) {
        final Matcher matcher = PLACEHOLDER.matcher(pattern);
        final StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            final String fieldName = matcher.group(1);
            final String value = extractField(pojo, fieldName);
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    value != null ? value : matcher.group(0)));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
