package io.questshift.session;

import io.questshift.campaign.Campaign;
import java.util.Map;

/** Overworld walk and YAML clue pickup. Does not score puzzles. */
public final class PresenceRules {

    public static final int SPAWN_SOUTH = 56;

    public static final int UNSET = 0;

    private PresenceRules() {}

    public static boolean roomUnlocked(
            String roomId, String currentRoomId, Map<String, Boolean> completed) {
        if (roomId == null || roomId.isBlank()) {
            return true;
        }
        if (roomId.equals(currentRoomId)) {
            return true;
        }
        return Boolean.TRUE.equals(completed.get(roomId));
    }

    public static Campaign.Clue clueInRoom(Campaign campaign, String roomId, String clueId) {
        if (campaign == null
                || roomId == null
                || roomId.isBlank()
                || clueId == null
                || clueId.isBlank()) {
            return null;
        }
        Campaign.Room room = campaign.roomById(roomId);
        if (room == null || room.clues == null) {
            return null;
        }
        for (Campaign.Clue clue : room.clues) {
            if (clueId.equals(clue.id)) {
                return clue;
            }
        }
        return null;
    }

    public static void spawnAt(GameSession.PartyMember member, Campaign.Room room) {
        if (member == null || room == null) {
            return;
        }
        if (member.mapX == UNSET && member.mapY == UNSET) {
            member.mapX = room.mapX;
            member.mapY = room.mapY + SPAWN_SOUTH;
        }
        if (member.viewedRoomId == null) {
            member.viewedRoomId = "";
        }
    }
}
