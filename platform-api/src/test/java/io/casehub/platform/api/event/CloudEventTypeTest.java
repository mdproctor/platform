package io.casehub.platform.api.event;

import jakarta.inject.Qualifier;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

class CloudEventTypeTest {

    @Test
    void annotation_isQualifier() {
        assertThat(CloudEventType.class.isAnnotationPresent(Qualifier.class)).isTrue();
    }

    @Test
    void annotation_hasRuntimeRetention() {
        Retention retention = CloudEventType.class.getAnnotation(Retention.class);
        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void annotation_hasValueMember() throws NoSuchMethodException {
        var method = CloudEventType.class.getMethod("value");
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }
}
