package io.casehub.platform.subscription.engine;

import io.casehub.platform.api.datasource.ObjectType;
import io.casehub.platform.api.subscription.SubscribableEvent;

import java.util.Objects;

public final class EventTypeObjectType implements ObjectType<Object> {

    private final String eventType;
    private final String prefix;

    public EventTypeObjectType(final String eventType) {
        this.eventType = Objects.requireNonNull(eventType);
        if (eventType.equals("*")) {
            this.prefix = "";
        } else if (eventType.endsWith(".*")) {
            this.prefix = eventType.substring(0, eventType.length() - 1);
        } else {
            this.prefix = null;
        }
    }

    @Override
    public boolean matches(final Object object) {
        final String pojoType = extractEventType(object);
        if (pojoType == null) {return false;}
        if (prefix != null) {
            return pojoType.startsWith(prefix) && pojoType.length() > prefix.length();
        }
        return eventType.equals(pojoType);
    }

    @Override
    public Object getTypeKey() {
        return eventType;
    }

    public static String extractEventType(final Object object) {
        if (object instanceof SubscribableEvent event) {
            return event.type();
        }
        return null;
    }
}
