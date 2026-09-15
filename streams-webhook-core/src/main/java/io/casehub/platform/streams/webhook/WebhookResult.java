package io.casehub.platform.streams.webhook;

public record WebhookResult(int status, String errorMessage) {

    public static WebhookResult accepted() { return new WebhookResult(202, null); }
    public static WebhookResult badRequest(String msg) { return new WebhookResult(400, msg); }
    public static WebhookResult unauthorized() { return new WebhookResult(401, null); }
    public static WebhookResult notFound() { return new WebhookResult(404, null); }
}
