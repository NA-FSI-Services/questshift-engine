package io.questshift.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.questshift.campaign.Campaign;
import io.questshift.session.GameSession;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LLMServiceTest {

    private final LLMService llm = new LLMService();
    private final Campaign.Room room = new Campaign.Room();

    @BeforeEach
    void wire() {
        llm.mapper = new ObjectMapper();
        llm.enabled = false;
        llm.baseUrl = "http://example.invalid/v1/";
        llm.model = "ibm-granite/granite-3.1-8b-instruct";
        llm.apiKey = "none";
        llm.timeoutSeconds = 1;
        lastAuthorization = null;
        room.narrative = "Authored beat.";
        room.puzzleType = "linux";
        room.expectedCommandPattern = "grep.*rune";
        room.hint = "pipe";
    }

    @Test
    void disabledNarrateUsesYamlFallback() {
        Campaign campaign = new Campaign();
        campaign.metadata.title = "Demo";
        LLMService.GameMasterTurn turn = llm.narrate(campaign, new GameSession(), room, "Opening.");
        assertTrue(turn.narrative.contains("Opening."));
        assertTrue(turn.narrative.contains("Authored beat."));
        assertEquals("grep.*rune", turn.expectedCommandPattern);
        assertEquals("linux", turn.puzzleType);
    }

    @Test
    void parseTurnKeepsYamlRegex() {
        LLMService.GameMasterTurn turn =
                llm.parseTurn(
                        """
                        ```json
                        {"narrative":"GM flavor","puzzle_type":"java","expected_command_pattern":"SHOULD_NOT_WIN","hint":"try pipes","canvas_event":"focus_room"}
                        ```
                        """,
                        room);
        assertEquals("GM flavor", turn.narrative);
        assertEquals("grep.*rune", turn.expectedCommandPattern);
        assertEquals("java", turn.puzzleType);
        assertEquals("try pipes", turn.hint);
    }

    @Test
    void parseTurnNullIsNull() {
        assertNull(llm.parseTurn(null, room));
    }

    @Test
    void parseTurnWrapsNonJson() {
        LLMService.GameMasterTurn turn = llm.parseTurn("not json", room);
        assertEquals("not json", turn.narrative);
        assertEquals("grep.*rune", turn.expectedCommandPattern);
    }

    @Test
    void chatResponseFirstContent() {
        LLMService.ChatResponse response = new LLMService.ChatResponse();
        assertNull(response.firstContent());
        response.choices = List.of();
        assertNull(response.firstContent());
        LLMService.Choice emptyMessage = new LLMService.Choice();
        response.choices = List.of(emptyMessage);
        assertNull(response.firstContent());
        LLMService.Choice choice = new LLMService.Choice();
        choice.message = new LLMService.Message();
        choice.message.content = "hi";
        response.choices = List.of(choice);
        assertEquals("hi", response.firstContent());
    }

    @Test
    void enabledNarrateFallsBackWhenVllmUnreachable() {
        llm.enabled = true;
        llm.apiKey = "none";
        llm.baseUrl = "http://localhost:1/v1";
        Campaign campaign = demoCampaign();
        LLMService.GameMasterTurn turn = llm.narrate(campaign, new GameSession(), room, null);
        assertEquals("Authored beat.", turn.narrative);
        assertEquals("grep.*rune", turn.expectedCommandPattern);
    }

    @Test
    void enabledNarrateKeepsYamlRegexWhenGmJsonArrives() throws Exception {
        String content =
                "{\"narrative\":\"Live GM\",\"puzzle_type\":\"linux\","
                        + "\"expected_command_pattern\":\"HACKED\",\"hint\":\"pipes\","
                        + "\"canvas_event\":\"focus_room\"}";
        try (AutoCloseable ignored = serveCompletions(200, openaiBody(content))) {
            llm.enabled = true;
            llm.apiKey = "none";
            Campaign campaign = demoCampaign();
            LLMService.GameMasterTurn turn = llm.narrate(campaign, new GameSession(), room, null);
            assertEquals("Live GM", turn.narrative);
            assertEquals("grep.*rune", turn.expectedCommandPattern);
            assertEquals("pipes", turn.hint);
        }
    }

    @Test
    void enabledNarrateFallsBackOnHttpError() throws Exception {
        try (AutoCloseable ignored = serveCompletions(503, "{\"error\":\"down\"}")) {
            llm.enabled = true;
            llm.apiKey = "none";
            Campaign campaign = demoCampaign();
            LLMService.GameMasterTurn turn = llm.narrate(campaign, new GameSession(), room, null);
            assertEquals("Authored beat.", turn.narrative);
            assertEquals("grep.*rune", turn.expectedCommandPattern);
        }
    }

    @Test
    void enabledNarrateSendsBearerWhenApiKeyIsSet() throws Exception {
        String content =
                "{\"narrative\":\"Keyed GM\",\"puzzle_type\":\"linux\","
                        + "\"expected_command_pattern\":\"HACKED\",\"hint\":\"pipes\","
                        + "\"canvas_event\":\"focus_room\"}";
        try (AutoCloseable ignored = serveCompletions(200, openaiBody(content))) {
            llm.enabled = true;
            llm.apiKey = "local-dev-key";
            Campaign campaign = demoCampaign();
            LLMService.GameMasterTurn turn = llm.narrate(campaign, new GameSession(), room, null);
            assertEquals("Keyed GM", turn.narrative);
            assertEquals("grep.*rune", turn.expectedCommandPattern);
            assertEquals("Bearer local-dev-key", lastAuthorization);
        }
    }

    @Test
    void parseTurnUsesRoomDefaultsForBlankFields() {
        LLMService.GameMasterTurn turn =
                llm.parseTurn("{\"narrative\":\"\",\"puzzle_type\":null,\"hint\":\" \"}", room);
        assertEquals("Authored beat.", turn.narrative);
        assertEquals("linux", turn.puzzleType);
        assertEquals("pipe", turn.hint);
        assertEquals("focus_room", turn.canvasEvent);
    }

    private static Campaign demoCampaign() {
        Campaign campaign = new Campaign();
        campaign.metadata.title = "Demo";
        campaign.gameMaster.systemPrompt = "Stay in the dungeon.";
        return campaign;
    }

    private static String openaiBody(String content) throws Exception {
        return new ObjectMapper()
                .writeValueAsString(
                        Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
    }

    private String lastAuthorization;

    private AutoCloseable serveCompletions(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        llm.baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        return () -> server.stop(0);
    }
}
