package io.questshift.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.questshift.campaign.Campaign;
import io.questshift.session.GameSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** OpenAI-compatible client aimed at vLLM serving Granite 3.2 8B Instruct. */
@ApplicationScoped
public class LLMService {

    private static final int HTTP_ERROR_STATUS = 300;
    private static final Logger LOG = Logger.getLogger(LLMService.class);

    @ConfigProperty(name = "questshift.llm.base-url")
    String baseUrl;

    @ConfigProperty(name = "questshift.llm.model")
    String model;

    @ConfigProperty(name = "questshift.llm.api-key", defaultValue = "none")
    String apiKey;

    @ConfigProperty(name = "questshift.llm.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "questshift.llm.timeout-seconds", defaultValue = "45")
    int timeoutSeconds;

    @Inject ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder().build();

    public GameMasterTurn narrate(
            Campaign campaign, GameSession session, Campaign.Room room, String extra) {
        GameMasterTurn fallback = fallbackTurn(room, extra);
        if (!enabled) {
            return fallback;
        }
        try {
            String body =
                    mapper.writeValueAsString(
                            new ChatRequest(
                                    model,
                                    List.of(
                                            new ChatMessage(
                                                    "system", campaign.gameMaster.systemPrompt),
                                            new ChatMessage(
                                                    "user",
                                                    userPrompt(campaign, session, room, extra))),
                                    0.4,
                                    700));
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body));
            if (apiKey != null && !apiKey.isBlank() && !"none".equals(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= HTTP_ERROR_STATUS) {
                LOG.warnf("vLLM HTTP %d: %s", response.statusCode(), response.body());
                return fallback;
            }
            ChatResponse parsed = mapper.readValue(response.body(), ChatResponse.class);
            String content = parsed.firstContent();
            GameMasterTurn turn = parseTurn(content, room);
            return turn == null ? fallback : turn;
        } catch (Exception e) {
            LOG.warn("vLLM unavailable, using campaign fallback", e);
            return fallback;
        }
    }

    GameMasterTurn parseTurn(String content, Campaign.Room room) {
        if (content == null) {
            return null;
        }
        String json = extractJson(content);
        try {
            JsonNode node = mapper.readTree(json);
            GameMasterTurn turn = new GameMasterTurn();
            turn.narrative = text(node, "narrative", room.narrative);
            turn.puzzleType = text(node, "puzzle_type", room.puzzleType);
            turn.expectedCommandPattern = room.expectedCommandPattern;
            turn.hint = text(node, "hint", room.hint);
            turn.canvasEvent = text(node, "canvas_event", "focus_room");
            return turn;
        } catch (Exception e) {
            LOG.debug("Could not parse Game Master JSON, wrapping raw text");
            GameMasterTurn turn = fallbackTurn(room, null);
            turn.narrative = content;
            return turn;
        }
    }

    private GameMasterTurn fallbackTurn(Campaign.Room room, String extra) {
        GameMasterTurn turn = new GameMasterTurn();
        turn.narrative =
                extra == null || extra.isBlank() ? room.narrative : extra + "\n\n" + room.narrative;
        turn.puzzleType = room.puzzleType;
        turn.expectedCommandPattern = room.expectedCommandPattern;
        turn.hint = room.hint;
        turn.canvasEvent = "focus_room";
        return turn;
    }

    private String userPrompt(
            Campaign campaign, GameSession session, Campaign.Room room, String extra) {
        return """
                Campaign: %s
                Elapsed seconds: %d
                Current room: %s (%s)
                Puzzle type: %s
                Room prompt: %s
                Authored narrative: %s
                Expected command pattern (do not rewrite): %s
                Inventory: %s
                Extra: %s

                Return JSON only:
                {"narrative":"...","puzzle_type":"%s","expected_command_pattern":"%s","hint":"...","canvas_event":"focus_room"}
                """
                .formatted(
                        campaign.metadata.title,
                        session.elapsedSeconds,
                        room.title,
                        room.id,
                        room.puzzleType,
                        room.prompt,
                        room.narrative,
                        room.expectedCommandPattern,
                        session.inventory,
                        extra == null ? "" : extra,
                        room.puzzleType,
                        room.expectedCommandPattern.replace("\"", "\\\""));
    }

    private static String extractJson(String content) {
        int fence = content.indexOf("```");
        String src = content;
        if (fence >= 0) {
            int start = content.indexOf('{', fence);
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return content.substring(start, end + 1);
            }
        }
        int start = src.indexOf('{');
        int end = src.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return src.substring(start, end + 1);
        }
        return src;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? fallback
                : value.asText();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static class GameMasterTurn {
        public String narrative;
        public String puzzleType;
        public String expectedCommandPattern;
        public String hint;
        public String canvasEvent;
    }

    public record ChatMessage(String role, String content) {}

    public record ChatRequest(
            String model,
            List<ChatMessage> messages,
            double temperature,
            @JsonProperty("max_tokens") int maxTokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatResponse {
        public List<Choice> choices;

        String firstContent() {
            if (choices == null || choices.isEmpty() || choices.getFirst().message == null) {
                return null;
            }
            return choices.getFirst().message.content;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        public Message message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        public String content;
    }
}
