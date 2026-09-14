package io.questshift.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.questshift.campaign.Campaign;
import io.questshift.session.GameSession;
import java.util.List;
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
        llm.apiKey = "secret";
        llm.baseUrl = "http://127.0.0.1:1/v1";
        Campaign campaign = new Campaign();
        campaign.metadata.title = "Demo";
        campaign.gameMaster.systemPrompt = "Stay in the dungeon.";
        LLMService.GameMasterTurn turn = llm.narrate(campaign, new GameSession(), room, null);
        assertEquals("Authored beat.", turn.narrative);
        assertEquals("grep.*rune", turn.expectedCommandPattern);
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
}
