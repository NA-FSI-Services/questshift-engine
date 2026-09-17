package io.questshift.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.questshift.campaign.Campaign;
import io.questshift.session.GameSession;
import io.questshift.session.SessionService;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GameResourceTest {

    private static final String ADA_PARTY =
            "{\"campaignId\":\"devops-dungeon\",\"party\":[{\"name\":\"Ada\",\"seatId\":\"guardian\"}]}";

    @Inject SessionService sessions;

    @BeforeEach
    void clearParties() {
        sessions.clear();
    }

    @Test
    void startSessionUsesYamlWhenLlmDisabled() {
        Map<?, ?> session =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
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
        assertEquals(Boolean.TRUE, session.get("yamlFallback"));
        assertNotNull(session.get("id"));
        assertTrue(String.valueOf(session.get("joinCode")).matches("[a-z]+-[a-z]+"));
    }

    @Test
    void acceptedExamplesClearTheHourThenExportImport() throws Exception {
        Campaign campaign = loadCampaign();
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
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
                .body("metadata.id", hasItem("devops-dungeon"))
                .body("[0].rooms[0].clues.id", hasItem("shell-log"))
                .body(
                        "[0].rooms.guardian.sprite",
                        hasItems(
                                "guardian_shell",
                                "guardian_playbook",
                                "guardian_pod",
                                "guardian_servlet",
                                "guardian_throne"));
    }

    @Test
    void missingSessionIsNotFound() {
        given().when().get("/api/sessions/missing").then().statusCode(404);
    }

    @Test
    void secondStartCreatesAnotherParty() {
        String first =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("joinCode");
        String second =
                given().contentType(ContentType.JSON)
                        .body(
                                "{\"campaignId\":\"devops-dungeon\",\"party\":[{\"name\":\"Linus\",\"seatId\":\"automancer\"}]}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("joinCode");
        assertNotNull(first);
        assertNotNull(second);
        assertFalse(first.equals(second));
        given().when()
                .get("/api/sessions/" + first)
                .then()
                .statusCode(200)
                .body("status", equalTo("active"));
        given().when()
                .get("/api/sessions/" + second)
                .then()
                .statusCode(200)
                .body("status", equalTo("active"))
                .body("partyMembers[0].name", equalTo("Linus"));
    }

    @Test
    void getSessionAcceptsJoinCodeCaseInsensitively() {
        Map<?, ?> session =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .as(Map.class);
        String id = String.valueOf(session.get("id"));
        String joinCode = String.valueOf(session.get("joinCode"));
        given().when()
                .get("/api/sessions/" + joinCode.toUpperCase(Locale.ROOT))
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("joinCode", equalTo(joinCode));
    }

    @Test
    void startSucceedsAfterTheHourExpires() {
        String id =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");
        GameSession live = sessions.get(id);
        live.startedAt = Instant.now().minusSeconds(3601);
        given().contentType(ContentType.JSON)
                .body(ADA_PARTY)
                .when()
                .post("/api/sessions")
                .then()
                .statusCode(200);
        given().when()
                .get("/api/sessions/" + id)
                .then()
                .statusCode(200)
                .body("status", equalTo("expired"));
    }

    @Test
    void importOfAnActivePartySitsBesideALiveHour() {
        given().contentType(ContentType.JSON)
                .body(ADA_PARTY)
                .when()
                .post("/api/sessions")
                .then()
                .statusCode(200);
        given().contentType(ContentType.TEXT)
                .queryParam("format", "yaml")
                .body(
                        "id: 11111111-2222-3333-4444-555555555555\n"
                                + "campaignId: devops-dungeon\n"
                                + "status: active\n"
                                + "currentRoomId: room-01-broken-shell\n")
                .when()
                .post("/api/sessions/import")
                .then()
                .statusCode(200)
                .body("id", equalTo("11111111-2222-3333-4444-555555555555"))
                .body("status", equalTo("active"));
    }

    @Test
    void leaveRemovesTheAliasAndDeleteDropsTheParty() {
        String joinCode =
                given().contentType(ContentType.JSON)
                        .body(
                                "{\"campaignId\":\"devops-dungeon\",\"party\":[{\"name\":\"Ada\",\"seatId\":\"guardian\"},{\"name\":\"Linus\",\"seatId\":\"automancer\"}]}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("joinCode");
        given().queryParam("name", "Ada")
                .when()
                .delete("/api/sessions/" + joinCode + "/party")
                .then()
                .statusCode(200)
                .body("partyMembers.name", hasItem("Linus"))
                .body("partyMembers.name", not(hasItem("Ada")));
        given().queryParam("name", "Nobody")
                .when()
                .delete("/api/sessions/" + joinCode + "/party")
                .then()
                .statusCode(200)
                .body("partyMembers.name", hasItem("Linus"));
        given().when()
                .delete("/api/sessions/" + joinCode + "/party")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_party"));
        given().when().delete("/api/sessions/" + joinCode).then().statusCode(204);
        given().when().get("/api/sessions/" + joinCode).then().statusCode(404);
    }

    @Test
    void emptyCommandFailsWithoutAdvancing() {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
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
                .body("session.currentRoomId", equalTo("room-01-broken-shell"))
                .body("session.commandLog.size()", equalTo(0));
    }

    @Test
    void commandLogRecordsAliasAndOutcomeOnTheSession() {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");
        given().contentType(ContentType.JSON)
                .body(
                        "{\"command\":\"cat /var/log/quest.log\",\"seatId\":\"guardian\",\"name\":\"Ada\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("passed", equalTo(false))
                .body("session.commandLog.size()", equalTo(1))
                .body("session.commandLog[0].name", equalTo("Ada"))
                .body("session.commandLog[0].seatId", equalTo("guardian"))
                .body("session.commandLog[0].roomId", equalTo("room-01-broken-shell"))
                .body("session.commandLog[0].passed", equalTo(false))
                .body("session.commandLog[0].command", equalTo("cat /var/log/quest.log"));
        given().contentType(ContentType.JSON)
                .body("{\"command\":\"ls\",\"seatId\":\"guardian\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("session.commandLog[1].name", equalTo("Ada"))
                .body("session.commandLog[1].seatId", equalTo("guardian"));
    }

    @Test
    void brokenShellNameOnlyUsesAuthoredGolemMiss() {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");
        given().contentType(ContentType.JSON)
                .body("{\"command\":\"THORN\",\"seatId\":\"guardian\",\"name\":\"Ada\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("passed", equalTo(false))
                .body("session.currentRoomId", equalTo("room-01-broken-shell"))
                .body("message", containsString("filesystem"));
        given().contentType(ContentType.JSON)
                .body(
                        "{\"command\":\"grep -i rune /var/log/quest.log\",\"seatId\":\"guardian\",\"name\":\"Ada\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("passed", equalTo(false))
                .body("message", containsString("too long"));
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
    void startTreatsBlankCampaignWithAMemberAsDefaultDungeon() {
        given().contentType(ContentType.JSON)
                .body(
                        "{\"campaignId\":\"  \",\"party\":[{\"name\":\"Ada\",\"seatId\":\"guardian\"}]}")
                .when()
                .post("/api/sessions")
                .then()
                .statusCode(200)
                .body("campaignId", equalTo("devops-dungeon"))
                .body("partyMembers.size()", equalTo(1));
    }

    @Test
    void emptyPartyOnStartIsRejected() {
        given().contentType(ContentType.JSON)
                .body("{\"campaignId\":\"devops-dungeon\",\"party\":[]}")
                .when()
                .post("/api/sessions")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_party"));
    }

    @Test
    void importWithoutIdAssignsOneAndExportNonJsonStaysYaml() {
        Map<?, ?> missingId =
                given().contentType(ContentType.TEXT)
                        .queryParam("format", "yaml")
                        .body(
                                "campaignId: devops-dungeon\n"
                                        + "status: complete\n"
                                        + "currentRoomId: room-01-broken-shell\n")
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
                                "id: \"  \"\n"
                                        + "campaignId: devops-dungeon\n"
                                        + "status: complete\n"
                                        + "currentRoomId: room-01-broken-shell\n")
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
                        .body(ADA_PARTY)
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
                        .body(ADA_PARTY)
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

    @Test
    void joinAddsAUniqueAliasAndRejectsDuplicates() {
        String joinCode =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("joinCode");
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Linus\",\"seatId\":\"automancer\"}")
                .when()
                .post("/api/sessions/" + joinCode + "/party")
                .then()
                .statusCode(200)
                .body("partyMembers.size()", equalTo(2))
                .body("partyMembers[1].name", equalTo("Linus"));
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"ada\",\"seatId\":\"ranger\"}")
                .when()
                .post("/api/sessions/" + joinCode + "/party")
                .then()
                .statusCode(200)
                .body("partyMembers.size()", equalTo(2))
                .body("partyMembers[0].seatId", equalTo("guardian"));
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Briar\",\"seatId\":\"not-a-seat\"}")
                .when()
                .post("/api/sessions/" + joinCode + "/party")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_party"));
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Briar\",\"seatId\":\"guardian\"}")
                .when()
                .post("/api/sessions/" + joinCode + "/party")
                .then()
                .statusCode(200)
                .body("partyMembers.size()", equalTo(3));
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Briar\",\"seatId\":\"artificer\"}")
                .when()
                .post("/api/sessions/" + joinCode + "/party")
                .then()
                .statusCode(200)
                .body("partyMembers.size()", equalTo(3));
    }

    @Test
    void ninthPlayerIsRejected() {
        String id =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");
        for (int i = 2; i <= 8; i++) {
            given().contentType(ContentType.JSON)
                    .body("{\"name\":\"Player" + i + "\",\"seatId\":\"guardian\"}")
                    .when()
                    .post("/api/sessions/" + id + "/party")
                    .then()
                    .statusCode(200);
        }
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Player9\",\"seatId\":\"guardian\"}")
                .when()
                .post("/api/sessions/" + id + "/party")
                .then()
                .statusCode(409)
                .body("error", equalTo("party_full"));
    }

    @Test
    void presenceWalksEntersAndPicksAClue() {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .body("partyMembers[0].mapX", equalTo(120))
                        .body("partyMembers[0].mapY", equalTo(276))
                        .extract()
                        .path("id");
        given().contentType(ContentType.JSON)
                .body(
                        "{\"name\":\"Ada\",\"mapX\":450,\"mapY\":360,\"viewedRoomId\":\"room-01-broken-shell\",\"pickupClueId\":\"shell-log\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/presence")
                .then()
                .statusCode(200)
                .body("partyMembers[0].viewedRoomId", equalTo("room-01-broken-shell"))
                .body("partyMembers[0].mapX", equalTo(450))
                .body("foundClues", hasItem("shell-log"))
                .body("partyMembers[0].foundClues", hasItem("shell-log"))
                .body("currentRoomId", equalTo("room-01-broken-shell"));
        given().contentType(ContentType.JSON)
                .body(
                        "{\"name\":\"Ada\",\"mapX\":450,\"mapY\":360,\"viewedRoomId\":\"room-02-playbook-of-binding\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/presence")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_presence"));
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Moss\",\"mapX\":1,\"mapY\":1}")
                .when()
                .post("/api/sessions/" + sessionId + "/presence")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_presence"));
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
