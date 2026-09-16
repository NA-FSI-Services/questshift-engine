package io.questshift.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.questshift.campaign.Campaign;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PresenceRulesTest {

    @Test
    void overworldAndCurrentAndCompletedAreUnlocked() {
        Map<String, Boolean> done = Map.of("room-01-broken-shell", true);
        assertTrue(PresenceRules.roomUnlocked("", "room-02-playbook-of-binding", done));
        assertTrue(
                PresenceRules.roomUnlocked(
                        "room-02-playbook-of-binding", "room-02-playbook-of-binding", done));
        assertTrue(
                PresenceRules.roomUnlocked(
                        "room-01-broken-shell", "room-02-playbook-of-binding", done));
        assertFalse(
                PresenceRules.roomUnlocked(
                        "room-03-pod-that-would-not-wake", "room-02-playbook-of-binding", done));
    }

    @Test
    void clueMustBelongToTheViewedRoom() {
        Campaign campaign = new Campaign();
        Campaign.Room room = new Campaign.Room();
        room.id = "room-01-broken-shell";
        Campaign.Clue clue = new Campaign.Clue();
        clue.id = "shell-log";
        room.clues = List.of(clue);
        campaign.rooms = List.of(room);
        assertEquals("shell-log", PresenceRules.clueInRoom(campaign, room.id, "shell-log").id);
        assertNull(PresenceRules.clueInRoom(campaign, room.id, "missing"));
        assertNull(PresenceRules.clueInRoom(campaign, "", "shell-log"));
        assertNull(PresenceRules.clueInRoom(campaign, "room-02-playbook-of-binding", "shell-log"));
    }

    @Test
    void spawnFillsUnsetCoordsSouthOfTheRoom() {
        Campaign.Room room = new Campaign.Room();
        room.mapX = 120;
        room.mapY = 220;
        GameSession.PartyMember member = new GameSession.PartyMember("Ada", "guardian");
        PresenceRules.spawnAt(member, room);
        assertEquals(120, member.mapX);
        assertEquals(220 + PresenceRules.SPAWN_SOUTH, member.mapY);
        assertEquals("", member.viewedRoomId);
        member.mapX = 10;
        member.mapY = 11;
        PresenceRules.spawnAt(member, room);
        assertEquals(10, member.mapX);
        assertEquals(11, member.mapY);
    }
}
