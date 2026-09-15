package io.questshift.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Live campaign state. Serialized to YAML/JSON for export and cluster restart restore. */
public class GameSession {

    public String id;
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

    /** True when the last GM turn used campaign YAML because vLLM was off or unreachable. */
    public boolean yamlFallback;

    public static class PartyMember {
        public String name;
        public String seatId;

        public PartyMember() {}

        public PartyMember(String name, String seatId) {
            this.name = name;
            this.seatId = seatId;
        }
    }

    public void tickElapsed() {
        elapsedSeconds = Math.max(0, Instant.now().getEpochSecond() - startedAt.getEpochSecond());
    }
}
