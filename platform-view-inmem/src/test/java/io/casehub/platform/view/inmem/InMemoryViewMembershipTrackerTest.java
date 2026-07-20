package io.casehub.platform.view.inmem;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryViewMembershipTrackerTest {

    private final InMemoryViewMembershipTracker tracker = new InMemoryViewMembershipTracker();

    @Test
    void getLastKnownMembershipReturnsEmptyForUnknown() {
        assertThat(tracker.getLastKnownMembership(UUID.randomUUID())).isEmpty();
    }

    @Test
    void updateThenGet() {
        var subjectId = UUID.randomUUID();
        var viewId    = UUID.randomUUID();
        tracker.updateMembership(subjectId, Map.of(viewId, "view-1"));

        assertThat(tracker.getLastKnownMembership(subjectId))
                .containsEntry(viewId, "view-1");
    }

    @Test
    void updateReplacePrevious() {
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
    void removeMembership() {
        var subjectId = UUID.randomUUID();
        tracker.updateMembership(subjectId, Map.of(UUID.randomUUID(), "v"));
        tracker.removeMembership(subjectId);
        assertThat(tracker.getLastKnownMembership(subjectId)).isEmpty();
    }

    @Test
    void removeMembershipNonExistentDoesNotThrow() {
        tracker.removeMembership(UUID.randomUUID());
    }

    @Test
    void returnedMapIsDefensiveCopy() {
        var subjectId = UUID.randomUUID();
        tracker.updateMembership(subjectId, Map.of(UUID.randomUUID(), "v"));

        var a = tracker.getLastKnownMembership(subjectId);
        var b = tracker.getLastKnownMembership(subjectId);
        assertThat(a).isNotSameAs(b);
    }

    @Test
    void bulkGetReturnsOnlyTrackedSubjects() {
        var s1        = UUID.randomUUID();
        var s2        = UUID.randomUUID();
        var untracked = UUID.randomUUID();
        var v1        = UUID.randomUUID();
        var v2        = UUID.randomUUID();
        tracker.updateMembership(s1, Map.of(v1, "view-1"));
        tracker.updateMembership(s2, Map.of(v2, "view-2"));

        var result = tracker.getLastKnownMembership(Set.of(s1, s2, untracked));

        assertThat(result).hasSize(2);
        assertThat(result.get(s1)).containsEntry(v1, "view-1");
        assertThat(result.get(s2)).containsEntry(v2, "view-2");
        assertThat(result).doesNotContainKey(untracked);
    }

    @Test
    void bulkGetEmptySetReturnsEmpty() {
        assertThat(tracker.getLastKnownMembership(Set.of())).isEmpty();
    }

    @Test
    void getSubjectsByView_returnsMatchingSubjects() {
        var s1 = UUID.randomUUID();
        var s2 = UUID.randomUUID();
        var s3 = UUID.randomUUID();
        var v1 = UUID.randomUUID();
        var v2 = UUID.randomUUID();
        tracker.updateMembership(s1, Map.of(v1, "View A", v2, "View B"));
        tracker.updateMembership(s2, Map.of(v1, "View A"));
        tracker.updateMembership(s3, Map.of(v2, "View B"));

        assertThat(tracker.getSubjectsByView(v1)).containsExactlyInAnyOrder(s1, s2);
    }

    @Test
    void getSubjectsByView_unknownView_returnsEmpty() {
        tracker.updateMembership(UUID.randomUUID(), Map.of(UUID.randomUUID(), "v"));
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
