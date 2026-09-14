package io.questshift.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.questshift.campaign.Campaign;
import io.restassured.http.ContentType;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GameResourceTest {

    @Test
    void startSessionUsesYamlWhenLlmDisabled() {
        Map<?, ?> session =
                given().contentType(ContentType.JSON)
                        .body("{}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .as(Map.class);

        assertEquals("devops-dungeon", session.get("campaignId"));
        assertEquals("room-01-broken-shell", session.get("currentRoomId"));
        assertEquals("active", session.get("status"));
        String narrative = String.valueOf(session.get("lastNarrative"));
        assertTrue(narrative.contains("Torchlight"), narrative);
        assertNotNull(session.get("id"));
    }

    @Test
    void acceptedExamplesClearTheHourThenExportImport() throws Exception {
        Campaign campaign = loadCampaign();
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body("{\"campaignId\":\"devops-dungeon\"}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");

        for (Campaign.Room room :
                campaign.rooms.stream()
                        .sorted((a, b) -> Integer.compare(a.order, b.order))
                        .toList()) {
            String example = room.acceptedExamples.getFirst();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("command", example);
            body.put("seatId", "guardian");
            given().contentType(ContentType.JSON)
                    .body(body)
                    .when()
                    .post("/api/sessions/" + sessionId + "/commands")
                    .then()
                    .statusCode(200)
                    .body("passed", equalTo(true));
        }

        given().when()
                .get("/api/sessions/" + sessionId)
                .then()
                .statusCode(200)
                .body("status", equalTo("complete"))
                .body("inventory", hasItems("rune-thorn", "rune-ash", "rune-oak", "rune-iron"))
                .body("puzzleCompletion.room-01-broken-shell", equalTo(true))
                .body("puzzleCompletion.room-02-playbook-of-binding", equalTo(true))
                .body("puzzleCompletion.room-03-pod-that-would-not-wake", equalTo(true))
                .body("puzzleCompletion.room-04-cursed-servlet", equalTo(true))
                .body("puzzleCompletion.room-05-operators-throne", equalTo(true));

        String yaml =
                given().when()
                        .get("/api/sessions/" + sessionId + "/export?format=yaml")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();
        assertTrue(
                yaml.contains("campaignId: \"devops-dungeon\"")
                        || yaml.contains("campaignId: devops-dungeon"));

        Map<?, ?> restored =
                given().contentType(ContentType.TEXT)
                        .queryParam("format", "yaml")
                        .body(yaml)
                        .when()
                        .post("/api/sessions/import")
                        .then()
                        .statusCode(200)
                        .extract()
                        .as(Map.class);

        assertEquals("complete", restored.get("status"));
        @SuppressWarnings("unchecked")
        List<String> inventory = (List<String>) restored.get("inventory");
        assertTrue(
                inventory.containsAll(List.of("rune-thorn", "rune-ash", "rune-oak", "rune-iron")));
        @SuppressWarnings("unchecked")
        Map<String, Boolean> completion = (Map<String, Boolean>) restored.get("puzzleCompletion");
        assertFalse(completion.containsValue(false));
        assertEquals(5, completion.size());
    }

    @Test
    void listCampaignsIncludesDevopsDungeon() {
        given().when()
                .get("/api/campaigns")
                .then()
                .statusCode(200)
                .body("metadata.id", hasItem("devops-dungeon"));
    }

    @Test
    void missingSessionIsNotFound() {
        given().when().get("/api/sessions/missing").then().statusCode(404);
    }

    @Test
    void emptyCommandFailsWithoutAdvancing() {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body("{}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");
        given().contentType(ContentType.JSON)
                .body("{\"command\":\"\",\"seatId\":\"guardian\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("passed", equalTo(false))
                .body("session.currentRoomId", equalTo("room-01-broken-shell"));
    }

    @Test
    void startHonorsCustomParty() {
        given().contentType(ContentType.JSON)
                .body(
                        "{\"campaignId\":\"devops-dungeon\",\"party\":[{\"name\":\"Ada\",\"seatId\":\"guardian\"}]}")
                .when()
                .post("/api/sessions")
                .then()
                .statusCode(200)
                .body("partyMembers[0].name", equalTo("Ada"))
                .body("partyMembers.size()", equalTo(1));
    }

    @Test
    void startTreatsBlankCampaignAndEmptyPartyAsDefaults() {
        given().contentType(ContentType.JSON)
                .body("{\"campaignId\":\"  \",\"party\":[]}")
                .when()
                .post("/api/sessions")
                .then()
                .statusCode(200)
                .body("campaignId", equalTo("devops-dungeon"))
                .body("partyMembers.size()", equalTo(4));
    }

    @Test
    void importWithoutIdAssignsOneAndExportNonJsonStaysYaml() {
        Map<?, ?> missingId =
                given().contentType(ContentType.TEXT)
                        .queryParam("format", "yaml")
                        .body("campaignId: devops-dungeon\ncurrentRoomId: room-01-broken-shell\n")
                        .when()
                        .post("/api/sessions/import")
                        .then()
                        .statusCode(200)
                        .extract()
                        .as(Map.class);
        assertNotNull(missingId.get("id"));
        Map<?, ?> blankId =
                given().contentType(ContentType.TEXT)
                        .queryParam("format", "yaml")
                        .body(
                                "id: \"  \"\ncampaignId: devops-dungeon\ncurrentRoomId: room-01-broken-shell\n")
                        .when()
                        .post("/api/sessions/import")
                        .then()
                        .statusCode(200)
                        .extract()
                        .as(Map.class);
        assertNotNull(blankId.get("id"));
        assertFalse(String.valueOf(blankId.get("id")).isBlank());
        String id = String.valueOf(missingId.get("id"));
        given().when().get("/api/sessions/" + id + "/export?format=").then().statusCode(200);
    }

    @Test
    void defaultExportIsYaml() {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body("{}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");
        given().when().get("/api/sessions/" + sessionId + "/export").then().statusCode(200);
    }

    @Test
    void jsonExportImportRoundTrip() {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body("{}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");
        String json =
                given().when()
                        .get("/api/sessions/" + sessionId + "/export?format=json")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();
        given().contentType(ContentType.JSON)
                .queryParam("format", "json")
                .body(json)
                .when()
                .post("/api/sessions/import")
                .then()
                .statusCode(200)
                .body("id", equalTo(sessionId))
                .body("campaignId", equalTo("devops-dungeon"));
    }

    private static Campaign loadCampaign() throws Exception {
        try (InputStream in =
                Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream("campaigns/campaign-devops-dungeon.yaml")) {
            assertNotNull(in, "classpath campaign missing");
            return new ObjectMapper(new YAMLFactory()).readValue(in, Campaign.class);
        }
    }
}
