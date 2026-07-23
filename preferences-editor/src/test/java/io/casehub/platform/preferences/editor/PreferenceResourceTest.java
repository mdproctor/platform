package io.casehub.platform.preferences.editor;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class PreferenceResourceTest {

    @Test
    void set_and_list_preference() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"namespace": "test", "name": "count", "subKey": "", "value": "42"}
                """)
            .queryParam("scope", "casehubio/devtown")
        .when()
            .put("/preferences")
        .then()
            .statusCode(204);

        given()
            .queryParam("scope", "casehubio/devtown")
        .when()
            .get("/preferences")
        .then()
            .statusCode(200)
            .body("size()", is(1))
            .body("[0].name", is("count"))
            .body("[0].value", is("42"));
    }

    @Test
    void delete_single_preference() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"namespace": "test", "name": "toDelete", "subKey": "", "value": "x"}
                """)
            .queryParam("scope", "casehubio/del")
        .when()
            .put("/preferences")
        .then()
            .statusCode(204);

        given()
            .queryParam("scope", "casehubio/del")
            .queryParam("namespace", "test")
            .queryParam("name", "toDelete")
        .when()
            .delete("/preferences")
        .then()
            .statusCode(204);

        given()
            .queryParam("scope", "casehubio/del")
        .when()
            .get("/preferences")
        .then()
            .statusCode(200)
            .body("size()", is(0));
    }

    @Test
    void delete_without_name_returns_400() {
        given()
            .queryParam("scope", "casehubio")
            .queryParam("namespace", "test")
        .when()
            .delete("/preferences")
        .then()
            .statusCode(400);
    }

    @Test
    void delete_namespace() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"namespace": "bulk", "name": "a", "subKey": "", "value": "1"}
                """)
            .queryParam("scope", "casehubio/bulk")
        .when()
            .put("/preferences")
        .then()
            .statusCode(204);

        given()
            .queryParam("scope", "casehubio/bulk")
            .queryParam("namespace", "bulk")
        .when()
            .delete("/preferences/by-namespace")
        .then()
            .statusCode(204);

        given()
            .queryParam("scope", "casehubio/bulk")
        .when()
            .get("/preferences")
        .then()
            .statusCode(200)
            .body("size()", is(0));
    }

    @Test
    void set_upserts_existing_preference() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"namespace": "test", "name": "upsert", "subKey": "", "value": "first"}
                """)
            .queryParam("scope", "casehubio/upsert")
        .when()
            .put("/preferences")
        .then()
            .statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"namespace": "test", "name": "upsert", "subKey": "", "value": "second"}
                """)
            .queryParam("scope", "casehubio/upsert")
        .when()
            .put("/preferences")
        .then()
            .statusCode(204);

        given()
            .queryParam("scope", "casehubio/upsert")
        .when()
            .get("/preferences")
        .then()
            .statusCode(200)
            .body("size()", is(1))
            .body("[0].value", is("second"));
    }
}
