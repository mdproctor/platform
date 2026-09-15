package io.casehub.platform.spring.rest;

import io.casehub.platform.preferences.editor.PreferenceSchemaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@RequestMapping("/preferences/schema")
public class PreferenceSchemaRestController {

    private final PreferenceSchemaService service;

    public PreferenceSchemaRestController(PreferenceSchemaService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Object> schema(
            @RequestParam(required = false) String namespace,
            WebRequest webRequest) {
        var result = service.schema(namespace);
        String etag = result.version();
        if (webRequest.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok()
                .eTag("\"" + etag + "\"")
                .body(result.schemas());
    }
}
