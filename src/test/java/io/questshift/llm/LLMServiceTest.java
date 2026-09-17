package io.questshift.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        llm.model = "ibm-granite/granite-3.2-8b-instruct";
        llm.apiKey = "none";
        llm.timeoutSeconds = 1;
        lastAuthorization = null;
        lastRequestBody = null;
        room.narrative = "Authored beat.";
        room.puzzleType = "linux";
        room.expectedCommandPattern = "grep.*rune";
        room.hint = "pipe";
    }

    @Test
    void userPromptIncludesSubmissionAcceptedExampleAndChatterCoaching() {
        Campaign campaign = demoCampaign();
        room.acceptedExamples = List.of("grep -i rune /var/log/quest.log | awk '{print $NF}'");
        room.title = "The Broken Shell";
        room.id = "room-01-broken-shell";
        room.prompt = "Pipe the log.";
        String prompt =
                llm.userPrompt(
                        campaign,
                        new GameSession(),
                        room,
                        LLMService.PromptContext.attempt(
                                "Nothing happens. The pattern does not bind.",
                                "Hello",
                                false,
                                null));
        assertTrue(prompt.contains("Hello"), prompt);
        assertTrue(prompt.contains("awk '{print $NF}'"), prompt);
        assertTrue(prompt.contains("YAML scorer result: failed"), prompt);
        assertTrue(prompt.contains("greeting"), prompt);
        assertTrue(prompt.contains("command / YAML / oc / Java snippet"), prompt);
        assertTrue(prompt.contains("solving command"), prompt);
        assertTrue(prompt.contains("Do not quote the intended example"), prompt);
    }

    @Test
    void sceneBeatPromptWithholdsSubmissionAndAcceptedExample() {
        Campaign campaign = demoCampaign();
        room.title = "The Pod That Would Not Wake";
        room.id = "room-03-pod-that-would-not-wake";
        room.puzzleType = "openshift";
        room.prompt = "Set the probe.";
        room.acceptedExamples =
                List.of(
                        "oc set probe pod/crashing-wizard --liveness --get-url=http://:8080/healthz -n dungeon");
        String extra =
                "The party just solved the previous room. The following is that room's authored"
                        + " success beat — not a player command for THIS room.\n\nThe familiar"
                        + " yields. The name is written.";
        String prompt =
                llm.userPrompt(
                        campaign, new GameSession(), room, LLMService.PromptContext.scene(extra));
        assertTrue(prompt.contains("scene beat, not a scored attempt"), prompt);
        assertTrue(prompt.contains("(none — this is a scene beat)"), prompt);
        assertTrue(prompt.contains("(withheld — scene beat, not a scored attempt)"), prompt);
        assertTrue(prompt.contains("Do not critique Extra as a wrong command"), prompt);
        assertFalse(prompt.contains("hosts: dungeon"), prompt);
        assertFalse(prompt.contains("crashing-wizard"), prompt);
        assertFalse(prompt.contains("gather_facts"), prompt);
    }

    @Test
    void missFallbackDoesNotLeakAcceptedExample() {
        room.acceptedExamples = List.of("grep -i rune /var/log/quest.log | awk '{print $NF}'");
        LLMService.GameMasterTurn turn =
                llm.narrateAttempt(
                        demoCampaign(),
                        new GameSession(),
                        room,
                        "Nothing happens. The pattern does not bind.",
                        "Hello",
                        false,
                        null);
        assertTrue(turn.yamlFallback);
        assertTrue(turn.narrative.contains("Nothing happens"), turn.narrative);
        assertFalse(turn.narrative.contains("awk"), turn.narrative);
        assertFalse(turn.narrative.contains("grep -i rune"), turn.narrative);
    }

    @Test
    void passAttemptPromptKeepsCallerIntendedExample() {
        Campaign campaign = demoCampaign();
        room.acceptedExamples = List.of("NEXT-ROOM-WIN");
        String prompt =
                llm.userPrompt(
                        campaign,
                        new GameSession(),
                        room,
                        LLMService.PromptContext.attempt(
                                "The golem yields.",
                                "grep -i rune /var/log/quest.log | awk '{print $NF}'",
                                true,
                                "grep -i rune /var/log/quest.log | awk '{print $NF}'"));
        assertTrue(prompt.contains("YAML scorer result: passed"), prompt);
        assertTrue(prompt.contains("Celebrate"), prompt);
        assertTrue(prompt.contains("grep -i rune /var/log/quest.log"), prompt);
        assertFalse(prompt.contains("NEXT-ROOM-WIN"), prompt);
    }

    @Test
    void firstAcceptedExampleStripsOrFallsBack() {
        assertEquals("(none authored)", LLMService.firstAcceptedExample(null));
        assertEquals("(none authored)", LLMService.firstAcceptedExample(room));
        room.acceptedExamples = List.of("  oc get pods  ");
        assertEquals("oc get pods", LLMService.firstAcceptedExample(room));
        room.acceptedExamples = List.of("   ");
        assertEquals("(none authored)", LLMService.firstAcceptedExample(room));
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
        assertTrue(turn.yamlFallback);
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
        assertFalse(turn.yamlFallback);
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
        assertFalse(turn.yamlFallback);
    }

    @Test
    void parseTurnExtractsNarrativeFromInvalidRegexEscapes() {
        String content =
                "{\"narrative\":\"A shell golem blocks the gate.\","
                        + "\"puzzle_type\":\"linux\","
                        + "\"expected_command_pattern\":\"(?s).*awk.*\\$NF.*\","
                        + "\"hint\":\"Use grep.\","
                        + "\"canvas_event\":\"focus_room\"}";
        LLMService.GameMasterTurn turn = llm.parseTurn(content, room);
        assertEquals("A shell golem blocks the gate.", turn.narrative);
        assertEquals("linux", turn.puzzleType);
        assertEquals("Use grep.", turn.hint);
        assertEquals("grep.*rune", turn.expectedCommandPattern);
        assertFalse(turn.narrative.contains("expected_command_pattern"));
        assertFalse(turn.yamlFallback);
    }

    @Test
    void parseTurnDoesNotDumpGmJsonWhenNarrativeIsMissing() {
        LLMService.GameMasterTurn turn =
                llm.parseTurn("{\"puzzle_type\":\"linux\",\"canvas_event\":\"focus_room\"}", room);
        assertEquals("Authored beat.", turn.narrative);
        assertFalse(turn.narrative.contains("canvas_event"));
        assertFalse(turn.yamlFallback);
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
        assertTrue(turn.yamlFallback);
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
            assertFalse(turn.yamlFallback);
        }
    }

    @Test
    void enabledSceneBeatOmitsNextRoomWinFromChatBody() throws Exception {
        String content =
                "{\"narrative\":\"The crashing-wizard ghost bars the north.\","
                        + "\"puzzle_type\":\"openshift\","
                        + "\"expected_command_pattern\":\"HACKED\",\"hint\":\"probe\","
                        + "\"canvas_event\":\"focus_room\"}";
        room.title = "The Pod That Would Not Wake";
        room.puzzleType = "openshift";
        room.acceptedExamples =
                List.of(
                        "oc set probe pod/crashing-wizard --liveness --get-url=http://:8080/healthz -n dungeon");
        try (AutoCloseable ignored = serveCompletions(200, openaiBody(content))) {
            llm.enabled = true;
            llm.apiKey = "none";
            Campaign campaign = demoCampaign();
            LLMService.GameMasterTurn turn =
                    llm.narrate(
                            campaign,
                            new GameSession(),
                            room,
                            "The party just solved the previous room. The familiar yields.");
            assertFalse(turn.yamlFallback);
            assertTrue(lastRequestBody.contains("withheld"), lastRequestBody);
            assertTrue(
                    lastRequestBody.contains("Do not critique Extra as a wrong command"),
                    lastRequestBody);
            assertFalse(lastRequestBody.contains("crashing-wizard"), lastRequestBody);
            assertFalse(lastRequestBody.contains("hosts: dungeon"), lastRequestBody);
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
            assertTrue(turn.yamlFallback);
        }
    }

    @Test
    void enabledNarrateAttemptSendsSubmissionAndAcceptedExample() throws Exception {
        String content =
                "{\"narrative\":\"Stay your tongue. I need a command that can bind this room.\","
                        + "\"puzzle_type\":\"linux\","
                        + "\"expected_command_pattern\":\"HACKED\",\"hint\":\"pipes\","
                        + "\"canvas_event\":\"focus_room\"}";
        room.acceptedExamples = List.of("grep -i rune /var/log/quest.log | awk '{print $NF}'");
        try (AutoCloseable ignored = serveCompletions(200, openaiBody(content))) {
            llm.enabled = true;
            llm.apiKey = "none";
            Campaign campaign = demoCampaign();
            LLMService.GameMasterTurn turn =
                    llm.narrateAttempt(
                            campaign,
                            new GameSession(),
                            room,
                            "Nothing happens. The pattern does not bind.",
                            "Hello",
                            false,
                            null);
            assertEquals(
                    "Stay your tongue. I need a command that can bind this room.", turn.narrative);
            assertEquals("grep.*rune", turn.expectedCommandPattern);
            assertFalse(turn.yamlFallback);
            assertTrue(lastRequestBody.contains("Hello"), lastRequestBody);
            assertTrue(lastRequestBody.contains("awk"), lastRequestBody);
            assertTrue(lastRequestBody.contains("YAML scorer result: failed"), lastRequestBody);
            assertTrue(lastRequestBody.contains("greeting"), lastRequestBody);
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

    private String lastRequestBody;

    private AutoCloseable serveCompletions(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                    lastRequestBody =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
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
