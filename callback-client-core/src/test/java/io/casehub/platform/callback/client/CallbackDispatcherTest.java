package io.casehub.platform.callback.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CallbackDispatcher dispatcher = new CallbackDispatcher(mapper);

    @Test
    void dispatch_missing_header_returns_forbidden() {
        var result = dispatcher.dispatch("test-spi", "method", null, null);
        assertThat(result.status()).isEqualTo(403);
    }

    @Test
    void dispatch_unknown_spi_returns_not_found() {
        var result = dispatcher.dispatch("unknown", "method", "test-spi", null);
        assertThat(result.status()).isEqualTo(404);
    }

    @Test
    void dispatch_unknown_method_returns_not_found() {
        dispatcher.registerSpi("test-spi", new TestBean());
        var result = dispatcher.dispatch("test-spi", "nonexistent", "test-spi", null);
        assertThat(result.status()).isEqualTo(404);
    }

    @Test
    void dispatch_void_method_returns_no_content() {
        dispatcher.registerSpi("test-spi", new TestBean());
        var result = dispatcher.dispatch("test-spi", "doSomething", "test-spi", null);
        assertThat(result.status()).isEqualTo(204);
    }

    @Test
    void dispatch_with_args_returns_ok() {
        dispatcher.registerSpi("test-spi", new TestBean());
        ArrayNode args = mapper.createArrayNode().add("hello");
        var result = dispatcher.dispatch("test-spi", "echo", "test-spi", args);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).isEqualTo("hello");
    }

    @Test
    void dispatch_method_that_throws_returns_error() {
        dispatcher.registerSpi("test-spi", new TestBean());
        var result = dispatcher.dispatch("test-spi", "throwError", "test-spi", null);
        assertThat(result.status()).isEqualTo(500);
    }

    public static class TestBean {
        public void doSomething() {}
        public String echo(String input) { return input; }
        public void throwError() { throw new RuntimeException("boom"); }
    }
}
