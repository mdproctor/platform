package io.casehub.platform.event;

import io.casehub.platform.api.event.CloudEventType;
import jakarta.enterprise.util.AnnotationLiteral;

public final class CloudEventTypeLiteral extends AnnotationLiteral<CloudEventType> implements CloudEventType {

    private final String value;

    public CloudEventTypeLiteral(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
