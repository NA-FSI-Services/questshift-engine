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
        assertEquals("Ada", session.get("turnName"));
        String narrative = String.valueOf(session.get("lastNarrative"));
        assertTrue(narrative.contains("Torchlight"), narrative);
        assertTrue(narrative.contains("Ada, the floor is yours."), narrative);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gmLog = (List<Map<String, Object>>) session.get("gmLog");
        assertEquals(1, gmLog.size());
        assertEquals("room-01-broken-shell", gmLog.getFirst().get("roomId"));
        assertTrue(String.valueOf(gmLog.getFirst().get("narrative")).contains("Torchlight"));
        assertTrue(
                String.valueOf(gmLog.getFirst().get("narrative"))
                        .contains("Ada, the floor is yours."));
        assertFalse(gmLog.getFirst().containsKey("name"));
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
                .body("puzzleCompletion.room-05-operators-throne", equalTo(true))
                .body("adventureSummary.mostCommands", equalTo("Ada"))
                .body("adventureSummary.stages.size()", equalTo(5))
                .body("adventureSummary.prose", containsString("The hour is complete"))
                .body("adventureSummary.prose", containsString("The Operator's Throne — Ada"));

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
    void listCampaignsIncludesBothShippedCards() {
        given().when()
                .get("/api/campaigns")
                .then()
                .statusCode(200)
                .body("metadata.id", hasItems("devops-dungeon", "ansible-bastion"))
                .body(
                        "find { it.metadata.id == 'devops-dungeon' }.rooms[0].clues.id",
                        hasItem("shell-log"))
                .body(
                        "find { it.metadata.id == 'devops-dungeon' }.story.clues.id",
                        hasItem("lobby-hour"))
                .body(
                        "find { it.metadata.id == 'devops-dungeon' }.rooms.guardian.sprite",
                        hasItems(
                                "guardian_shell",
                                "guardian_playbook",
                                "guardian_pod",
                                "guardian_servlet",
                                "guardian_throne"))
                .body(
                        "find { it.metadata.id == 'ansible-bastion' }.rooms[0].id",
                        equalTo("room-01-couriers-vault"))
                .body(
                        "find { it.metadata.id == 'ansible-bastion' }.rooms.puzzle_type",
                        hasItem("ansible"));
    }

    @Test
    void ansibleBastionAcceptedExamplesClearTheHour() throws Exception {
        Campaign campaign = loadCampaign("campaigns/campaign-ansible-bastion.yaml");
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(
                                "{\"campaignId\":\"ansible-bastion\",\"party\":[{\"name\":\"Ada\",\"seatId\":\"automancer\"}]}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .body("campaignId", equalTo("ansible-bastion"))
                        .body("currentRoomId", equalTo("room-01-couriers-vault"))
                        .extract()
                        .path("id");

        for (Campaign.Room room :
                campaign.rooms.stream()
                        .sorted((a, b) -> Integer.compare(a.order, b.order))
                        .toList()) {
            String example = room.acceptedExamples.getFirst();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("command", example);
            body.put("seatId", "automancer");
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
                .body(
                        "inventory",
                        hasItems(
                                "rune-quill",
                                "rune-lamp",
                                "rune-gate",
                                "rune-sigil",
                                "controller-aether"))
                .body("puzzleCompletion.room-01-couriers-vault", equalTo(true))
                .body("puzzleCompletion.room-05-controllers-throne", equalTo(true));
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
                .body("partyMembers.name", not(hasItem("Ada")))
                .body("turnName", equalTo("Linus"))
                .body("lastNarrative", containsString("Linus, the floor is yours."));
        given().queryParam("name", "Nobody")
                .when()
                .delete("/api/sessions/" + joinCode + "/party")
                .then()
                .statusCode(200)
                .body("partyMembers.name", hasItem("Linus"))
                .body("turnName", equalTo("Linus"));
        given().when()
                .delete("/api/sessions/" + joinCode + "/party")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_party"));
        given().when().delete("/api/sessions/" + joinCode).then().statusCode(204);
        given().when().get("/api/sessions/" + joinCode).then().statusCode(404);
    }

    @Test
    void completeHourFreezesClockAndRejectsFurtherCommands() {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(ADA_PARTY)
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");
        GameSession live = sessions.get(sessionId);
        live.status = "complete";
        live.elapsedSeconds = 42;
        live.startedAt = Instant.now().minusSeconds(90);
        GameSession frozen = sessions.get(sessionId);
        assertEquals("complete", frozen.status);
        assertEquals(42, frozen.elapsedSeconds);
        assertNotNull(frozen.adventureSummary);
        given().contentType(ContentType.JSON)
                .body("{\"command\":\"Hello\",\"seatId\":\"guardian\",\"name\":\"Ada\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(409)
                .body("error", equalTo("party_not_active"));
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
                .body("session.commandLog[0].command", equalTo("cat /var/log/quest.log"))
                .body("session.commandLog[0].narrative", containsString("cursed form"));
        given().contentType(ContentType.JSON)
                .body("{\"command\":\"ls\",\"seatId\":\"guardian\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("session.commandLog[1].name", equalTo("Ada"))
                .body("session.commandLog[1].seatId", equalTo("guardian"))
                .body("session.commandLog[1].narrative", containsString("Nothing happens"));
    }

    @Test
    void gmRepliesStayAddressedToTheSpeakerAndSceneBeatsDoNot() {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(
                                "{\"campaignId\":\"devops-dungeon\",\"party\":["
                                        + "{\"name\":\"Ada\",\"seatId\":\"guardian\"},"
                                        + "{\"name\":\"Linus\",\"seatId\":\"automancer\"}]}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .body("turnName", equalTo("Ada"))
                        .body("gmLog.size()", equalTo(1))
                        .body("gmLog[0].roomId", equalTo("room-01-broken-shell"))
                        .body("gmLog[0].narrative", containsString("Torchlight"))
                        .body("gmLog[0].narrative", containsString("Ada, the floor is yours."))
                        .extract()
                        .path("id");
        given().contentType(ContentType.JSON)
                .body("{\"command\":\"Hello\",\"seatId\":\"automancer\",\"name\":\"Linus\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(409)
                .body("error", equalTo("not_your_turn"));
        given().when()
                .get("/api/sessions/" + sessionId)
                .then()
                .statusCode(200)
                .body("commandLog.size()", equalTo(0))
                .body("turnName", equalTo("Ada"));
        given().contentType(ContentType.JSON)
                .body("{\"command\":\"Hello\",\"seatId\":\"guardian\",\"name\":\"Ada\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("session.commandLog[0].name", equalTo("Ada"))
                .body("session.commandLog[0].narrative", containsString("Nothing happens"))
                .body(
                        "session.commandLog[0].narrative",
                        containsString("Linus, the floor is yours."))
                .body("session.turnName", equalTo("Linus"));
        given().contentType(ContentType.JSON)
                .body("{\"command\":\"THORN\",\"seatId\":\"automancer\",\"name\":\"Linus\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("session.commandLog[1].name", equalTo("Linus"))
                .body("session.commandLog[1].narrative", containsString("filesystem"))
                .body("session.commandLog[1].narrative", containsString("Ada, the floor is yours."))
                .body("session.turnName", equalTo("Ada"))
                .body("session.gmLog.size()", equalTo(1));
        given().contentType(ContentType.JSON)
                .body(
                        "{\"command\":\"grep -i rune /var/log/quest.log | awk '{print $NF}'\","
                                + "\"seatId\":\"guardian\",\"name\":\"Ada\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("passed", equalTo(true))
                .body("session.currentRoomId", equalTo("room-02-playbook-of-binding"))
                .body("session.turnName", equalTo("Linus"))
                .body("session.commandLog[2].roomId", equalTo("room-01-broken-shell"))
                .body("session.commandLog[2].name", equalTo("Ada"))
                .body("session.commandLog[2].narrative", containsString("The golem cracks"))
                .body(
                        "session.commandLog[2].narrative",
                        containsString("Linus, the floor is yours."))
                .body("session.commandLog[2].narrative", not(containsString("bound familiar")))
                .body("session.gmLog.size()", equalTo(2))
                .body("session.gmLog[1].roomId", equalTo("room-02-playbook-of-binding"))
                .body("session.gmLog[1].narrative", containsString("bound familiar"))
                .body("session.gmLog[1].narrative", containsString("Linus, the floor is yours."));

        String yaml =
                given().when()
                        .get("/api/sessions/" + sessionId + "/export?format=yaml")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();
        assertTrue(yaml.contains("turnName"), yaml);
        assertTrue(yaml.contains("Linus"), yaml);
        assertTrue(yaml.contains("name: Ada") || yaml.contains("name: \"Ada\""), yaml);
        assertTrue(yaml.contains("The golem cracks"), yaml);

        Map<?, ?> restored =
                given().contentType(ContentType.TEXT)
                        .queryParam("format", "yaml")
                        .body(yaml)
                        .when()
                        .post("/api/sessions/import")
                        .then()
                        .statusCode(200)
                        .body("turnName", equalTo("Linus"))
                        .extract()
                        .as(Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> log = (List<Map<String, Object>>) restored.get("commandLog");
        assertEquals("Ada", log.get(0).get("name"));
        assertEquals("Linus", log.get(1).get("name"));
        assertTrue(String.valueOf(log.get(1).get("narrative")).contains("filesystem"));
        assertTrue(String.valueOf(log.get(2).get("narrative")).contains("The golem cracks"));
        assertFalse(String.valueOf(log.get(2).get("narrative")).contains("bound familiar"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) restored.get("gmLog");
        assertEquals(2, scenes.size());
        assertFalse(scenes.get(0).containsKey("name"));
        assertFalse(scenes.get(1).containsKey("name"));
        assertEquals("room-02-playbook-of-binding", scenes.get(1).get("roomId"));
        assertTrue(String.valueOf(scenes.get(1).get("narrative")).contains("bound familiar"));
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
                        "{\"name\":\"Ada\",\"mapX\":450,\"mapY\":360,\"viewedRoomId\":\"room-01-broken-shell\",\"pickupClueId\":\"lobby-hour\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/presence")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_presence"));
        given().contentType(ContentType.JSON)
                .body(
                        "{\"name\":\"Ada\",\"mapX\":80,\"mapY\":380,\"viewedRoomId\":\"\",\"pickupClueId\":\"lobby-hour\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/presence")
                .then()
                .statusCode(200)
                .body("partyMembers[0].viewedRoomId", equalTo(""))
                .body("foundClues", hasItem("lobby-hour"))
                .body("partyMembers[0].foundClues", hasItem("lobby-hour"))
                .body("currentRoomId", equalTo("room-01-broken-shell"));
        given().contentType(ContentType.JSON)
                .body(
                        "{\"name\":\"Ada\",\"mapX\":80,\"mapY\":380,\"viewedRoomId\":\"\",\"pickupClueId\":\"shell-log\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/presence")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_presence"));
        given().contentType(ContentType.JSON)
                .body(
                        "{\"name\":\"Ada\",\"mapX\":450,\"mapY\":360,\"viewedRoomId\":\"room-01-broken-shell\",\"pickupClueId\":\"play-hosts\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/presence")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_presence"));
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
        return loadCampaign("campaigns/campaign-devops-dungeon.yaml");
    }

    private static Campaign loadCampaign(String resource) throws Exception {
        try (InputStream in =
                Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "classpath campaign missing: " + resource);
            return new ObjectMapper(new YAMLFactory()).readValue(in, Campaign.class);
        }
    }
}
