package io.casehub.platform.spring.rest;

import io.casehub.platform.api.subscription.SubscriptionInput;
import io.casehub.platform.api.subscription.SubscriptionPage;
import io.casehub.platform.api.subscription.SubscriptionScope;
import io.casehub.platform.api.subscription.SubscriptionUpdate;
import io.casehub.platform.subscription.SubscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/subscriptions")
public class SubscriptionRestController {

    private final SubscriptionService service;

    public SubscriptionRestController(SubscriptionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody SubscriptionInput input) {
        try {
            return ResponseEntity.status(201).body(service.create(input));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping
    public SubscriptionPage list(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) SubscriptionScope scope,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") int limit) {
        return service.list(enabled, scope, cursor, limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> getById(@PathVariable String id) {
        return service.getById(id)
                .map(s -> ResponseEntity.ok((Object) s))
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable String id, @RequestBody SubscriptionUpdate update) {
        try {
            return service.update(id, update)
                    .map(s -> ResponseEntity.ok((Object) s))
                    .orElse(ResponseEntity.notFound().build());
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        try {
            return service.delete(id)
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        }
    }

    @PatchMapping("/{id}/enable")
    public ResponseEntity<Object> enable(@PathVariable String id) {
        try {
            return service.enable(id)
                    .map(s -> ResponseEntity.ok((Object) s))
                    .orElse(ResponseEntity.notFound().build());
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        }
    }

    @PatchMapping("/{id}/disable")
    public ResponseEntity<Object> disable(@PathVariable String id) {
        try {
            return service.disable(id)
                    .map(s -> ResponseEntity.ok((Object) s))
                    .orElse(ResponseEntity.notFound().build());
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build();
        }
    }
}
