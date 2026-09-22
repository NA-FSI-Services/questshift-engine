package io.questshift.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class StateSerializerTest {

    @Test
    void yamlRoundTripPreservesInventory() {
        GameSession session = new GameSession();
        session.id = "demo";
        session.campaignId = "devops-dungeon";
        session.currentRoomId = "room-01-broken-shell";
        session.inventory.add("rune-thorn");
        session.puzzleCompletion.put("room-01-broken-shell", true);
        session.yamlFallback = true;
        session.joinCode = "thorn-golem";
        GameSession.CommandLogEntry attempt = new GameSession.CommandLogEntry();
        attempt.roomId = "room-01-broken-shell";
        attempt.name = "Ada";
        attempt.seatId = "guardian";
        attempt.command = "cat /var/log/quest.log";
        attempt.passed = false;
        attempt.message = "miss";
        attempt.narrative = "Ada, the golem hates a bare cat.";
        session.commandLog.add(attempt);
        GameSession.GmLogEntry opening = new GameSession.GmLogEntry();
        opening.roomId = "room-01-broken-shell";
        opening.narrative = "Torchlight. A shell golem blocks the gate.";
        session.gmLog.add(opening);
        session.foundClues.add("shell-log");
        session.status = "complete";
        session.elapsedSeconds = 42;
        GameSession.AdventureSummary recap = new GameSession.AdventureSummary();
        recap.mostQuestions = "Ada";
        recap.mostQuestionsCount = 1;
        recap.mostCommands = "Ada";
        recap.mostCommandsCount = 1;
        recap.prose = "The hour is complete.";
        session.adventureSummary = recap;
        GameSession.PartyMember ada = new GameSession.PartyMember("Ada", "guardian");
        ada.mapX = 120;
        ada.mapY = 276;
        ada.foundClues.add("shell-log");
        session.partyMembers.add(ada);
        StateSerializer serializer = new StateSerializer();
        GameSession restored = serializer.fromYaml(serializer.toYaml(session));
        assertEquals("demo", restored.id);
        assertEquals("rune-thorn", restored.inventory.getFirst());
        assertEquals(Boolean.TRUE, restored.puzzleCompletion.get("room-01-broken-shell"));
        assertTrue(restored.yamlFallback);
        assertEquals("thorn-golem", restored.joinCode);
        assertEquals(1, restored.commandLog.size());
        assertEquals("Ada", restored.commandLog.getFirst().name);
        assertEquals("cat /var/log/quest.log", restored.commandLog.getFirst().command);
        assertEquals("Ada, the golem hates a bare cat.", restored.commandLog.getFirst().narrative);
        assertEquals(1, restored.gmLog.size());
        assertEquals("room-01-broken-shell", restored.gmLog.getFirst().roomId);
        assertEquals(
                "Torchlight. A shell golem blocks the gate.", restored.gmLog.getFirst().narrative);
        assertEquals("complete", restored.status);
        assertEquals(42, restored.elapsedSeconds);
        assertEquals("Ada", restored.adventureSummary.mostQuestions);
        assertEquals("The hour is complete.", restored.adventureSummary.prose);
        assertEquals(List.of("shell-log"), restored.foundClues);
        assertEquals(120, restored.partyMembers.getFirst().mapX);
        assertEquals(276, restored.partyMembers.getFirst().mapY);
        assertEquals(List.of("shell-log"), restored.partyMembers.getFirst().foundClues);
    }

    @Test
    void jsonRoundTripAndFormatSniff() {
        GameSession session = new GameSession();
        session.id = "json-demo";
        session.campaignId = "devops-dungeon";
        StateSerializer serializer = new StateSerializer();
        GameSession fromJson = serializer.fromJson(serializer.toJson(session));
        assertEquals("json-demo", fromJson.id);
        GameSession sniffedYaml =
                serializer.from("id: sniffed\ncampaignId: devops-dungeon\n", null);
        assertEquals("sniffed", sniffedYaml.id);
        GameSession explicitYaml = serializer.from("campaignId: devops-dungeon\n", "yaml");
        assertEquals("devops-dungeon", explicitYaml.campaignId);
        GameSession docStart =
                serializer.from("---\nid: dashed\ncampaignId: devops-dungeon\n", null);
        assertEquals("dashed", docStart.id);
    }

    @Test
    void streamRoundTripAndInvalidBodies() throws Exception {
        GameSession session = new GameSession();
        session.id = "stream";
        session.campaignId = "devops-dungeon";
        StateSerializer serializer = new StateSerializer();

        ByteArrayOutputStream jsonOut = new ByteArrayOutputStream();
        serializer.writeJson(session, jsonOut);
        GameSession fromJsonStream =
                serializer.fromJson(new ByteArrayInputStream(jsonOut.toByteArray()));
        assertEquals("stream", fromJsonStream.id);

        ByteArrayOutputStream yamlOut = new ByteArrayOutputStream();
        serializer.writeYaml(session, yamlOut);
        GameSession fromYamlStream =
                serializer.fromYaml(new ByteArrayInputStream(yamlOut.toByteArray()));
        assertEquals("stream", fromYamlStream.id);

        assertThrows(IllegalArgumentException.class, () -> serializer.fromJson("{"));
        assertThrows(IllegalArgumentException.class, () -> serializer.fromYaml(": not yaml"));
    }
}
