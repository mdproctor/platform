package io.casehub.platform.spring.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.platform.callback.client.CallbackDispatcher;
import io.casehub.platform.callback.client.DispatchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/casehub/callbacks")
public class CallbackDispatchRestController {

    private final CallbackDispatcher dispatcher;

    public CallbackDispatchRestController(CallbackDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/{spiName}/{methodName}")
    public ResponseEntity<Object> dispatch(
            @PathVariable String spiName,
            @PathVariable String methodName,
            @RequestHeader(value = "X-CaseHub-SPI", required = false) String spiHeader,
            @RequestBody(required = false) JsonNode argsNode) {
        DispatchResult result = dispatcher.dispatch(spiName, methodName, spiHeader, argsNode);
        if (result.body() == null) {
            return ResponseEntity.status(result.status()).build();
        }
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
