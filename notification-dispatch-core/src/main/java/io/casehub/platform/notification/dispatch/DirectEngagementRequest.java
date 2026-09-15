package io.casehub.platform.notification.dispatch;

import io.casehub.platform.api.delivery.EngagementType;

public record DirectEngagementRequest(EngagementType type, String metadata) {}
