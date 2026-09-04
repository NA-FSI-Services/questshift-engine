package io.questshift.campaign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Campaign {

    public Metadata metadata = new Metadata();
    public List<Seat> seats = new ArrayList<>();
    public Story story = new Story();
    public List<Room> rooms = new ArrayList<>();
    @JsonProperty("game_master")
    public GameMaster gameMaster = new GameMaster();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        public String id;
        public String title;
        public String subtitle;
        public int durationMinutes = 60;
        public int recommendedPartySize = 4;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Seat {
        public String id;
        public String title;
        public String color;
        public String blurb;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Story {
        public String premise;
        public String opening;
        public String winCondition;
        public String failCondition;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Room {
        public String id;
        public int order;
        public String title;
        public int mapX;
        public int mapY;
        @JsonProperty("puzzle_type")
        public String puzzleType;
        public int estimatedMinutes;
        public String narrative;
        public String prompt;
        @JsonProperty("expected_command_pattern")
        public String expectedCommandPattern;
        @JsonProperty("forbidden_patterns")
        public List<String> forbiddenPatterns = new ArrayList<>();
        public String hint;
        @JsonProperty("success_narrative")
        public String successNarrative;
        public List<Loot> loot = new ArrayList<>();
        @JsonProperty("canvas_event")
        public String canvasEvent;
        @JsonProperty("skills_granted")
        public List<String> skillsGranted = new ArrayList<>();
        @JsonProperty("requires_loot")
        public List<String> requiresLoot = new ArrayList<>();
        @JsonProperty("accepted_examples")
        public List<String> acceptedExamples = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Loot {
        public String id;
        public String name;
        public String kind;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GameMaster {
        @JsonProperty("system_prompt")
        public String systemPrompt;
        @JsonProperty("output_schema")
        public Object outputSchema;
    }

    public Room roomById(String id) {
        return rooms.stream().filter(r -> r.id.equals(id)).findFirst().orElse(null);
    }

    public Room firstRoom() {
        return rooms.stream().min((a, b) -> Integer.compare(a.order, b.order)).orElse(null);
    }

    public Room nextRoom(String currentId) {
        Room current = roomById(currentId);
        if (current == null) {
            return firstRoom();
        }
        return rooms.stream()
                .filter(r -> r.order == current.order + 1)
                .findFirst()
                .orElse(null);
    }
}
