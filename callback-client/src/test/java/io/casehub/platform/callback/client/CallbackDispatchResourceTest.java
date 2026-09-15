package io.casehub.platform.callback.client;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CallbackDispatchResourceTest {

    @Inject
    CallbackDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher.registerSpi("test-spi", new TestSpi());
    }

    @Test
    void dispatch_validMethodCall_returnsResult() {
        given()
            .contentType(ContentType.JSON)
            .header("X-CaseHub-SPI", "test-spi")
            .body("[\"hello\"]")
        .when()
            .post("/casehub/callbacks/test-spi/greet")
        .then()
            .statusCode(200)
            .body(equalTo("Hello, hello!"));
    }

    @Test
    void dispatch_voidMethod_returns204() {
        given()
            .contentType(ContentType.JSON)
            .header("X-CaseHub-SPI", "test-spi")
            .body("[\"test\"]")
        .when()
            .post("/casehub/callbacks/test-spi/doWork")
        .then()
            .statusCode(204);
    }

    @Test
    void dispatch_unknownSpi_returns404() {
        given()
            .contentType(ContentType.JSON)
            .header("X-CaseHub-SPI", "test-spi")
            .body("[]")
        .when()
            .post("/casehub/callbacks/unknown-spi/someMethod")
        .then()
            .statusCode(404);
    }

    @Test
    void dispatch_unknownMethod_returns404() {
        given()
            .contentType(ContentType.JSON)
            .header("X-CaseHub-SPI", "test-spi")
            .body("[]")
        .when()
            .post("/casehub/callbacks/test-spi/nonexistent")
        .then()
            .statusCode(404);
    }

    @Test
    void dispatch_methodThrows_returns500() {
        given()
            .contentType(ContentType.JSON)
            .header("X-CaseHub-SPI", "test-spi")
            .body("[]")
        .when()
            .post("/casehub/callbacks/test-spi/fail")
        .then()
            .statusCode(500);
    }

    @Test
    void dispatch_missingSpiHeader_returns403() {
        given()
            .contentType(ContentType.JSON)
            .body("[]")
        .when()
            .post("/casehub/callbacks/test-spi/greet")
        .then()
            .statusCode(403);
    }

    public static class TestSpi {

        public String greet(final String name) {
            return "Hello, " + name + "!";
        }

        public void doWork(final String item) {
            // no-op
        }

        public void fail() {
            throw new RuntimeException("intentional test failure");
        }
    }
}
