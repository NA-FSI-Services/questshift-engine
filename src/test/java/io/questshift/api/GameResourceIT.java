package io.questshift.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
class GameResourceIT {

    private static final String ADA_PARTY =
            "{\"campaignId\":\"devops-dungeon\",\"party\":[{\"name\":\"Ada\",\"seatId\":\"guardian\"}]}";

    @Test
    void campaignsLoadFromPackagedApp() {
        given().when().get("/api/campaigns").then().statusCode(200);
    }

    @Test
    void startSessionOnPackagedAppUsesYamlWhenLlmDisabled() {
        given().when()
                .get("/api/sessions/" + startOrReuse())
                .then()
                .statusCode(200)
                .body("campaignId", equalTo("devops-dungeon"))
                .body("currentRoomId", equalTo("room-01-broken-shell"))
                .body("id", notNullValue());
    }

    @Test
    void missingSessionIsNotFound() {
        given().when().get("/api/sessions/does-not-exist").then().statusCode(404);
    }

    @Test
    void packagedAppRejectsEmptyCommandAndExportsYaml() {
        String sessionId = startOrReuse();

        given().contentType(ContentType.JSON)
                .body("{\"command\":\"\",\"seatId\":\"guardian\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("passed", equalTo(false))
                .body("session.currentRoomId", equalTo("room-01-broken-shell"));

        given().when().get("/api/sessions/" + sessionId + "/export").then().statusCode(200);
    }

    private static String startOrReuse() {
        var response = given().contentType(ContentType.JSON).body(ADA_PARTY).post("/api/sessions");
        if (response.statusCode() == Status.OK.getStatusCode()) {
            return response.path("id");
        }
        String joinCode = response.path("joinCode");
        return given().when()
                .get("/api/sessions/" + joinCode)
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }
}
