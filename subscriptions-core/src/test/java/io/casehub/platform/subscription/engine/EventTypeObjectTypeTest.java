package io.casehub.platform.subscription.engine;

import io.casehub.platform.api.subscription.SubscribableEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTypeObjectTypeTest {

    @Test
    void matches_trueForMatchingEventType() {
        var objectType = new EventTypeObjectType("io.casehub.work.workitem.completed");
        var pojo = new TestEvent("io.casehub.work.workitem.completed", "tenant-1");
        assertThat(objectType.matches(pojo)).isTrue();
    }

    @Test
    void matches_falseForDifferentEventType() {
        var objectType = new EventTypeObjectType("io.casehub.work.workitem.completed");
        var pojo = new TestEvent("io.casehub.work.workitem.created", "tenant-1");
        assertThat(objectType.matches(pojo)).isFalse();
    }

    @Test
    void matches_falseForPojoWithoutTypeMethod() {
        var objectType = new EventTypeObjectType("io.casehub.work.workitem.completed");
        assertThat(objectType.matches("not-a-pojo")).isFalse();
    }

    @Test
    void matches_falseForNull() {
        var objectType = new EventTypeObjectType("io.casehub.work.workitem.completed");
        assertThat(objectType.matches(null)).isFalse();
    }

    @Test
    void getTypeKey_returnsEventTypeString() {
        var objectType = new EventTypeObjectType("io.casehub.work.workitem.completed");
        assertThat(objectType.getTypeKey()).isEqualTo("io.casehub.work.workitem.completed");
    }

    @Test
    void constructor_rejectsNull() {
        assertThatThrownBy(() -> new EventTypeObjectType(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void extractEventType_returnsTypeFromPojoWithTypeMethod() {
        var pojo = new TestEvent("io.casehub.work.workitem.completed", "tenant-1");
        assertThat(EventTypeObjectType.extractEventType(pojo))
                .isEqualTo("io.casehub.work.workitem.completed");
    }

    @Test
    void extractEventType_returnsNullForPojoWithoutTypeMethod() {
        assertThat(EventTypeObjectType.extractEventType("no-type-method")).isNull();
    }

    @Test
    void extractEventType_returnsNullForNull() {
        assertThat(EventTypeObjectType.extractEventType(null)).isNull();
    }
// --- glob/prefix matching tests ---

    @Test
    void matches_globMatchesChildEventType() {
        var objectType = new EventTypeObjectType("io.casehub.work.workitem.*");
        assertThat(objectType.matches(new TestEvent("io.casehub.work.workitem.completed", "t"))).isTrue();
        assertThat(objectType.matches(new TestEvent("io.casehub.work.workitem.created", "t"))).isTrue();
    }

    @Test
    void matches_globDoesNotMatchDifferentPrefix() {
        var objectType = new EventTypeObjectType("io.casehub.work.workitem.*");
        assertThat(objectType.matches(new TestEvent("io.casehub.work.case.created", "t"))).isFalse();
    }

    @Test
    void matches_globDoesNotMatchExactPrefix() {
        // "io.casehub.work.workitem.*" should NOT match "io.casehub.work.workitem" (no trailing segment)
        var objectType = new EventTypeObjectType("io.casehub.work.workitem.*");
        assertThat(objectType.matches(new TestEvent("io.casehub.work.workitem", "t"))).isFalse();
    }

    @Test
    void matches_globMatchesDeepSuffix() {
        // Glob is a prefix match, not single-segment — deep suffixes match
        var objectType = new EventTypeObjectType("io.casehub.work.*");
        assertThat(objectType.matches(new TestEvent("io.casehub.work.workitem.completed", "t"))).isTrue();
    }

    @Test
    void matches_bareStarMatchesEverything() {
        var objectType = new EventTypeObjectType("*");
        assertThat(objectType.matches(new TestEvent("io.casehub.work.workitem.completed", "t"))).isTrue();
        assertThat(objectType.matches(new TestEvent("anything", "t"))).isTrue();
    }

    @Test
    void matches_bareStarDoesNotMatchNonSubscribable() {
        var objectType = new EventTypeObjectType("*");
        assertThat(objectType.matches("not-subscribable")).isFalse();
    }

    @Test
    void getTypeKey_returnsGlobPattern() {
        var objectType = new EventTypeObjectType("io.casehub.work.workitem.*");
        assertThat(objectType.getTypeKey()).isEqualTo("io.casehub.work.workitem.*");
    }


    record TestEvent(String type, String tenancyId) implements SubscribableEvent {}
}
