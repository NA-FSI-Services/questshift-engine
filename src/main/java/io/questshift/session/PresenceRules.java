package io.questshift.session;

import io.questshift.campaign.Campaign;
import java.util.List;
import java.util.Map;

/** Overworld walk and YAML clue pickup. Does not score puzzles. Lobby clues live on story.clues. */
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

    /**
     * Resolve a pickup against the current layer. Empty {@code roomId} is the overworld ({@code
     * story.clues}). A room id looks only at that room.
     */
    public static Campaign.Clue clueInRoom(Campaign campaign, String roomId, String clueId) {
        if (campaign == null || clueId == null || clueId.isBlank()) {
            return null;
        }
        String viewed = roomId == null ? "" : roomId.trim();
        if (viewed.isEmpty()) {
            return clueById(campaign.story == null ? null : campaign.story.clues, clueId);
        }
        Campaign.Room room = campaign.roomById(viewed);
        if (room == null) {
            return null;
        }
        return clueById(room.clues, clueId);
    }

    private static Campaign.Clue clueById(List<Campaign.Clue> clues, String clueId) {
        if (clues == null) {
            return null;
        }
        for (Campaign.Clue clue : clues) {
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
