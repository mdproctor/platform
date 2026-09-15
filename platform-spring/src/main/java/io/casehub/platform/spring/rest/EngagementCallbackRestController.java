package io.casehub.platform.spring.rest;

import io.casehub.platform.notification.dispatch.DirectEngagementRequest;
import io.casehub.platform.notification.dispatch.EngagementCallbackService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/delivery/engagement")
public class EngagementCallbackRestController {

    private final EngagementCallbackService service;

    public EngagementCallbackRestController(EngagementCallbackService service) {
        this.service = service;
    }

    @PostMapping("/callback/{channelId}")
    public ResponseEntity<Void> handleCallback(
            @PathVariable String channelId,
            @RequestBody String rawPayload,
            HttpServletRequest request) {
        try {
            service.handleCallback(channelId, rawPayload, extractHeaders(request));
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            return ResponseEntity.ok().build();
        }
    }

    @PostMapping("/{attemptId}")
    public ResponseEntity<Void> recordDirect(
            @PathVariable String attemptId,
            @RequestBody DirectEngagementRequest request) {
        try {
            service.recordDirect(attemptId, request.type(), request.metadata());
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(
                    e.getMessage().contains("not found") ? 404 : 400).build();
        }
    }

    private static Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
