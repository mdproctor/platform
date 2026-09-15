package io.casehub.platform.spring.rest;

import io.casehub.platform.api.subscription.EventTypeDescriptor;
import io.casehub.platform.subscription.EventTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/subscriptions/event-types")
public class EventTypeRestController {

    private final EventTypeService service;

    public EventTypeRestController(EventTypeService service) {
        this.service = service;
    }

    @GetMapping
    public Set<EventTypeDescriptor> listEventTypes() {
        return service.listEventTypes();
    }
}
