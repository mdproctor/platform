package io.casehub.platform.spring.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.callback.client.CallbackDispatcher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackDispatchRestControllerTest {

    private final CallbackDispatcher dispatcher = new CallbackDispatcher(new ObjectMapper());
    private final CallbackDispatchRestController controller = new CallbackDispatchRestController(dispatcher);

    @Test
    void dispatch_missing_header_returns_403() {
        var response = controller.dispatch("test-spi", "method", null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void dispatch_unknown_spi_returns_404() {
        var response = controller.dispatch("unknown", "method", "test-spi", null);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void dispatch_valid_call_returns_result() {
        dispatcher.registerSpi("test-spi", new TestBean());
        var mapper = new ObjectMapper();
        var args = mapper.createArrayNode().add("world");
        var response = controller.dispatch("test-spi", "echo", "test-spi", args);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("world");
    }

    public static class TestBean {
        public String echo(String input) { return input; }
    }
}
