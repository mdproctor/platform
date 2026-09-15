package io.casehub.platform.callback.client;

import java.util.Map;

public record DispatchResult(int status, Object body) {

    public static DispatchResult ok(Object body) { return new DispatchResult(200, body); }
    public static DispatchResult noContent() { return new DispatchResult(204, null); }
    public static DispatchResult notFound(String message) { return new DispatchResult(404, Map.of("error", message)); }
    public static DispatchResult forbidden(String message) { return new DispatchResult(403, Map.of("error", message)); }
    public static DispatchResult error(String message) { return new DispatchResult(500, Map.of("error", message)); }
}
