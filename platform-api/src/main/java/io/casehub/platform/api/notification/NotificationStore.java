package io.casehub.platform.api.notification;

import java.util.List;
import java.util.Optional;

/**
 * Notification persistence SPI — single blocking interface, no reactive counterpart.
 *
 * <p><strong>CDI Events:</strong> Non-no-op implementations must fire:
 * <ul>
 *   <li>{@link NotificationCreated} — after {@code store()} and for each notification
 *       in {@code storeAll()}</li>
 *   <li>{@link NotificationStatusChanged} — after {@code markRead()} and {@code dismiss()}</li>
 *   <li>{@link AllNotificationsRead} — after {@code markAllRead()}</li>
 * </ul>
 *
 * <p>The no-op {@code @DefaultBean} implementation must NOT fire events.
 *
 * <p><strong>User Ownership Enforcement:</strong> {@code markRead}, {@code dismiss}, and
 * {@code markAllRead} enforce user-level ownership via {@code userId} parameter.
 * Implementations WHERE clause includes {@code user_id = ? AND tenancy_id = ?}, making
 * authorization structural at the SPI boundary.
 *
 * <p><strong>Retention:</strong> Store-owned per protocol. No {@code delete} operation —
 * retention is implementation-specific (in-memory: bounded size eviction, JPA: scheduled
 * purge).
 *
 * <p><strong>Deduplication:</strong> Routing-layer responsibility. Same CloudEvent can
 * legitimately produce multiple notifications for the same user through different
 * subscription matches.
 */
public interface NotificationStore {

    /**
     * Store a single notification.
     *
     * <p>Generates UUID v7 id, sets {@link NotificationStatus#UNREAD}, captures
     * {@code createdAt} timestamp. Returns the persisted {@link Notification}.
     *
     * <p>Fires {@link NotificationCreated} event (non-no-op implementations only).
     *
     * @param input notification to store
     * @return the persisted notification with generated id and timestamps
     */
    Notification store(NotificationInput input);

    /**
     * Store multiple notifications atomically. No partial-failure semantics — failures
     * propagate immediately.
     *
     * <p>Fires {@link NotificationCreated} event for each notification
     * (non-no-op implementations only).
     *
     * @param inputs notifications to store
     * @return list of persisted notifications in input order
     */
    List<Notification> storeAll(List<NotificationInput> inputs);

    /**
     * Query notifications with cursor-based pagination.
     *
     * <p>Results ordered by {@code (createdAt DESC, id DESC)} — newest first, UUID v7
     * id breaks ties for same-timestamp notifications.
     *
     * <p>Cursor is opaque — encoding is implementation-owned. JPA uses keyset pagination
     * encoding {@code (createdAt, id)}; in-memory may use different scheme.
     *
     * @param query query parameters (user, tenant, optional status/category filters, cursor, limit)
     * @return page of notifications with optional next cursor
     */
    NotificationPage find(NotificationQuery query);

    /**
     * Count unread notifications for a user.
     *
     * @param userId    notification recipient
     * @param tenancyId tenant isolation
     * @return count of {@link NotificationStatus#UNREAD} notifications
     */
    long unreadCount(String userId, String tenancyId);

    /**
     * Mark a notification as {@link NotificationStatus#READ}. Sets {@code readAt} timestamp.
     *
     * <p>User ownership enforced: returns empty if notification not found, wrong tenant,
     * or wrong user. No information leak — empty is the same regardless of reason.
     *
     * <p>Fires {@link NotificationStatusChanged} event on success
     * (non-no-op implementations only).
     *
     * @param id        notification id
     * @param userId    notification owner (authorization check)
     * @param tenancyId tenant isolation (authorization check)
     * @return updated notification with new status and {@code readAt}, or empty if not found/unauthorized
     */
    Optional<Notification> markRead(String id, String userId, String tenancyId);

    /**
     * Mark a notification as {@link NotificationStatus#DISMISSED}. Sets {@code dismissedAt}
     * timestamp. Transition from {@link NotificationStatus#UNREAD} or
     * {@link NotificationStatus#READ} is allowed. DISMISSED is terminal.
     *
     * <p>User ownership enforced: returns empty if notification not found, wrong tenant,
     * or wrong user. No information leak — empty is the same regardless of reason.
     *
     * <p>Fires {@link NotificationStatusChanged} event on success
     * (non-no-op implementations only).
     *
     * @param id        notification id
     * @param userId    notification owner (authorization check)
     * @param tenancyId tenant isolation (authorization check)
     * @return updated notification with new status and {@code dismissedAt}, or empty if not found/unauthorized
     */
    Optional<Notification> dismiss(String id, String userId, String tenancyId);

    /**
     * Mark all {@link NotificationStatus#UNREAD} notifications as
     * {@link NotificationStatus#READ} for a user. Sets {@code readAt} timestamp on each.
     *
     * <p>Bulk operation — implementations should use a single UPDATE statement, not
     * per-row entity loads.
     *
     * <p>Fires {@link AllNotificationsRead} event with count
     * (non-no-op implementations only).
     *
     * @param userId    notification owner
     * @param tenancyId tenant isolation
     * @return count of notifications marked as read
     */
    int markAllRead(String userId, String tenancyId);
}
