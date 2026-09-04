package io.questshift.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateSerializerTest {

    @Test
    void yamlRoundTripPreservesInventory() {
        GameSession session = new GameSession();
        session.id = "demo";
        session.campaignId = "devops-dungeon";
        session.currentRoomId = "room-01-broken-shell";
        session.inventory.add("rune-thorn");
        session.puzzleCompletion.put("room-01-broken-shell", true);
        StateSerializer serializer = new StateSerializer();
        GameSession restored = serializer.fromYaml(serializer.toYaml(session));
        assertEquals("demo", restored.id);
        assertEquals("rune-thorn", restored.inventory.getFirst());
        assertEquals(Boolean.TRUE, restored.puzzleCompletion.get("room-01-broken-shell"));
    }
}
