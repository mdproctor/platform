package io.casehub.platform.streams.webhook;

import io.casehub.platform.api.endpoints.EndpointCapability;
import io.casehub.platform.api.endpoints.EndpointDescriptor;
import io.casehub.platform.api.endpoints.EndpointPropertyKeys;
import io.casehub.platform.api.endpoints.EndpointProtocol;
import io.casehub.platform.api.endpoints.EndpointRegistry;
import io.casehub.platform.api.endpoints.EndpointType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

@QuarkusTest
class WebhookResourceTest {

    @Inject
    EndpointRegistry endpointRegistry;

    @BeforeEach
    void registerStream() {
        endpointRegistry.register(new EndpointDescriptor(
            Path.of("streams", "my-stream"),
            TenancyConstants.DEFAULT_TENANT_ID,
            EndpointType.SERVICE,
            EndpointProtocol.HTTP,
            Map.of(EndpointPropertyKeys.STREAM_EVENT_TYPE, "io.casehub.test.event",
                   EndpointPropertyKeys.URL, "https://external.example.com/my-stream"),
            null,
            Set.of(EndpointCapability.RECEIVE)));
    }

    @Test
    void receive_valid_cloudevent_returns_202() {
        String body = """
                {
                  "specversion": "1.0",
                  "type": "io.example.event",
                  "source": "https://example.com",
                  "id": "test-id-1",
                  "data": {"key": "value"}
                }
                """;

        RestAssured.given()
            .contentType("application/cloudevents+json")
            .body(body)
            .when()
            .post("/streams/webhook/" + TenancyConstants.DEFAULT_TENANT_ID + "/my-stream")
            .then()
            .statusCode(202);
    }

    @Test
    void receive_invalid_body_returns_400() {
        RestAssured.given()
            .contentType("application/cloudevents+json")
            .body("not-valid-json")
            .when()
            .post("/streams/webhook/" + TenancyConstants.DEFAULT_TENANT_ID + "/my-stream")
            .then()
            .statusCode(400);
    }

    @Test
    void receive_unknown_stream_returns_404() {
        String body = """
                {"specversion":"1.0","type":"t","source":"s","id":"x"}
                """;

        RestAssured.given()
            .contentType("application/cloudevents+json")
            .body(body)
            .when()
            .post("/streams/webhook/" + TenancyConstants.DEFAULT_TENANT_ID + "/no-such-stream")
            .then()
            .statusCode(404);
    }

    @Test
    void receive_wrong_content_type_returns_415() {
        RestAssured.given()
            .contentType("application/json")
            .body("{}")
            .when()
            .post("/streams/webhook/" + TenancyConstants.DEFAULT_TENANT_ID + "/my-stream")
            .then()
            .statusCode(415);
    }
}
