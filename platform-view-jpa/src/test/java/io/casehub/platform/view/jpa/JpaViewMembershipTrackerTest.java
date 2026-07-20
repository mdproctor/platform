package io.casehub.platform.view.jpa;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestTransaction
class JpaViewMembershipTrackerTest {

    @Inject
    JpaViewMembershipTracker tracker;

    @Test
    void getUnknownReturnsEmpty() {
        assertThat(tracker.getLastKnownMembership(UUID.randomUUID())).isEmpty();
    }

    @Test
    void updateAndGet() {
        var subjectId = UUID.randomUUID();
        var viewId    = UUID.randomUUID();
        tracker.updateMembership(subjectId, Map.of(viewId, "view-1"));

        var result = tracker.getLastKnownMembership(subjectId);
        assertThat(result).containsEntry(viewId, "view-1");
    }

    @Test
    void updateReplacesPrevious() {
        var subjectId = UUID.randomUUID();
        var v1        = UUID.randomUUID();
        var v2        = UUID.randomUUID();
        tracker.updateMembership(subjectId, Map.of(v1, "old"));
        tracker.updateMembership(subjectId, Map.of(v2, "new"));

        var result = tracker.getLastKnownMembership(subjectId);
        assertThat(result).doesNotContainKey(v1);
        assertThat(result).containsEntry(v2, "new");
    }

    @Test
    void remove() {
        var subjectId = UUID.randomUUID();
        tracker.updateMembership(subjectId, Map.of(UUID.randomUUID(), "v"));
        tracker.removeMembership(subjectId);
        assertThat(tracker.getLastKnownMembership(subjectId)).isEmpty();
    }

    @Test
    void bulkGetReturnsOnlyTrackedSubjects() {
        var s1 = UUID.randomUUID();
        var s2 = UUID.randomUUID();
        var v1 = UUID.randomUUID();
        var v2 = UUID.randomUUID();
        tracker.updateMembership(s1, Map.of(v1, "view-1"));
        tracker.updateMembership(s2, Map.of(v2, "view-2"));

        var result = tracker.getLastKnownMembership(Set.of(s1, s2, UUID.randomUUID()));

        assertThat(result).hasSize(2);
        assertThat(result.get(s1)).containsEntry(v1, "view-1");
        assertThat(result.get(s2)).containsEntry(v2, "view-2");
    }

    @Test
    void bulkGetEmptySetReturnsEmpty() {
        assertThat(tracker.getLastKnownMembership(Set.of())).isEmpty();
    }

    @Test
    void updateTwiceSameViewWithinTransaction() {
        var subjectId = UUID.randomUUID();
        var viewId    = UUID.randomUUID();
        tracker.updateMembership(subjectId, Map.of(viewId, "first"));
        tracker.updateMembership(subjectId, Map.of(viewId, "second"));

        var result = tracker.getLastKnownMembership(subjectId);
        assertThat(result).hasSize(1);
        assertThat(result).containsEntry(viewId, "second");
    }


    @Test
    void getSubjectsByView_returnsMatchingSubjects() {
        var s1 = UUID.randomUUID();
        var s2 = UUID.randomUUID();
        var v1 = UUID.randomUUID();
        var v2 = UUID.randomUUID();
        tracker.updateMembership(s1, Map.of(v1, "View A", v2, "View B"));
        tracker.updateMembership(s2, Map.of(v1, "View A"));

        assertThat(tracker.getSubjectsByView(v1)).containsExactlyInAnyOrder(s1, s2);
    }

    @Test
    void getSubjectsByView_unknownView_returnsEmpty() {
        assertThat(tracker.getSubjectsByView(UUID.randomUUID())).isEmpty();
    }

    @Test
    void removeMembershipByView_removesAllRecordsForView() {
        var s1 = UUID.randomUUID();
        var s2 = UUID.randomUUID();
        var v1 = UUID.randomUUID();
        var v2 = UUID.randomUUID();
        tracker.updateMembership(s1, Map.of(v1, "View A", v2, "View B"));
        tracker.updateMembership(s2, Map.of(v1, "View A"));

        tracker.removeMembershipByView(v1);

        assertThat(tracker.getLastKnownMembership(s1))
                .containsExactlyEntriesOf(Map.of(v2, "View B"));
        assertThat(tracker.getLastKnownMembership(s2)).isEmpty();
    }

    @Test
    void removeMembershipByView_unknownView_isNoOp() {
        var subject = UUID.randomUUID();
        var view    = UUID.randomUUID();
        tracker.updateMembership(subject, Map.of(view, "View A"));

        tracker.removeMembershipByView(UUID.randomUUID());

        assertThat(tracker.getLastKnownMembership(subject))
                .containsExactlyEntriesOf(Map.of(view, "View A"));
    }

    @Test
    void removeMembershipByView_leavesOtherViewsIntact() {
        var subject = UUID.randomUUID();
        var v1      = UUID.randomUUID();
        var v2      = UUID.randomUUID();
        tracker.updateMembership(subject, Map.of(v1, "View A", v2, "View B"));

        tracker.removeMembershipByView(v1);

        assertThat(tracker.getLastKnownMembership(subject))
                .containsExactlyEntriesOf(Map.of(v2, "View B"));
    }
}
