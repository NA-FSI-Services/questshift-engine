package io.questshift.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Live campaign state. Serialized to YAML/JSON for export and cluster restart restore. */
public class GameSession {

    public String id;
    public String joinCode;
    public String campaignId;
    public String status = "active";
    public String currentRoomId;
    public Instant startedAt = Instant.now();
    public long elapsedSeconds;
    public List<PartyMember> partyMembers = new ArrayList<>();
    public List<String> inventory = new ArrayList<>();
    public List<String> skills = new ArrayList<>();
    public Map<String, Boolean> puzzleCompletion = new LinkedHashMap<>();
    public int hintCount;
    public String lastNarrative;
    public String lastHint;
    public String lastCanvasEvent;
    public List<CommandLogEntry> commandLog = new ArrayList<>();

    /** Room-addressed scene beats (Start, next-room opening). No player alias. */
    public List<GmLogEntry> gmLog = new ArrayList<>();

    public List<String> foundClues = new ArrayList<>();
    public AdventureSummary adventureSummary;

    /** True when the last GM turn used campaign YAML because vLLM was off or unreachable. */
    public boolean yamlFallback;

    public static class PartyMember {
        public String name;
        public String seatId;
        public int mapX;
        public int mapY;
        public String viewedRoomId = "";
        public List<String> foundClues = new ArrayList<>();

        public PartyMember() {}

        public PartyMember(String name, String seatId) {
            this.name = name;
            this.seatId = seatId;
        }
    }

    /**
     * One submitted attempt. {@code name} is who asked and who {@code narrative} answers. Seats do
     * not gate scoring.
     */
    public static class CommandLogEntry {
        public String roomId;
        public String name;
        public String seatId;
        public String command;
        public boolean passed;
        public String message;

        /** Game Master prose for this attempt. Distinct from the evaluator {@code message}. */
        public String narrative;

        public CommandLogEntry() {}
    }

    /** One scene beat addressed to the room, not to a player. */
    public static class GmLogEntry {
        public String roomId;
        public String narrative;

        public GmLogEntry() {}
    }

    /** Recap shown when the throne is cleared. Counts come from commandLog. */
    public static class AdventureSummary {
        public String mostQuestions;
        public int mostQuestionsCount;
        public String mostCommands;
        public int mostCommandsCount;
        public List<StageClear> stages = new ArrayList<>();
        public String prose;
    }

    public static class StageClear {
        public String roomId;
        public String roomTitle;
        public String name;
    }

    public void tickElapsed() {
        if ("complete".equals(status) || "expired".equals(status)) {
            return;
        }
        elapsedSeconds = Math.max(0, Instant.now().getEpochSecond() - startedAt.getEpochSecond());
    }
}
