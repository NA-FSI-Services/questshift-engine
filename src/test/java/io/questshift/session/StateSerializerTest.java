package io.questshift.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
        StateSerializer serializer = new StateSerializer();
        GameSession restored = serializer.fromYaml(serializer.toYaml(session));
        assertEquals("demo", restored.id);
        assertEquals("rune-thorn", restored.inventory.getFirst());
        assertEquals(Boolean.TRUE, restored.puzzleCompletion.get("room-01-broken-shell"));
        assertTrue(restored.yamlFallback);
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
