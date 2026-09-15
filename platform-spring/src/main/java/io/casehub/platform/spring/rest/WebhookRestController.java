package io.casehub.platform.spring.rest;

import io.casehub.platform.streams.webhook.WebhookReceiver;
import io.casehub.platform.streams.webhook.WebhookResult;
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
@RequestMapping("/streams/webhook")
public class WebhookRestController {

    private final WebhookReceiver receiver;

    public WebhookRestController(WebhookReceiver receiver) {
        this.receiver = receiver;
    }

    @PostMapping(value = "/{tenancyId}/{streamId}", consumes = "application/cloudevents+json")
    public ResponseEntity<Object> receive(
            @RequestBody byte[] body,
            @PathVariable String tenancyId,
            @PathVariable String streamId,
            HttpServletRequest request) {
        WebhookResult result = receiver.receive(body, tenancyId, streamId, extractHeaders(request));
        if (result.errorMessage() != null) {
            return ResponseEntity.status(result.status()).body(result.errorMessage());
        }
        return ResponseEntity.status(result.status()).build();
    }

    private static Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
