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

    private static final char JSON_QUOTE = '"';

    private static final char JSON_ESCAPE = '\\';

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

    // vLLM/uvicorn on OpenShift rejects Java's default HTTP/2 cleartext POSTs
    // (FastAPI 400: body Field required / input None). HTTP/1.1 matches curl.
    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public GameMasterTurn narrate(
            Campaign campaign, GameSession session, Campaign.Room room, String extra) {
        return narrate(campaign, session, room, PromptContext.scene(extra));
    }

    /**
     * Score already ran. Send the player submission, the speaker alias, and the intended accepted
     * example so Granite can address that player (including chatter like "Hello") without rewriting
     * YAML.
     */
    public GameMasterTurn narrateAttempt(
            Campaign campaign,
            GameSession session,
            Campaign.Room room,
            String extra,
            String playerSubmission,
            boolean yamlPassed,
            String intendedExample,
            String speakerAlias) {
        return narrate(
                campaign,
                session,
                room,
                PromptContext.attempt(
                        extra, playerSubmission, yamlPassed, intendedExample, speakerAlias));
    }

    private GameMasterTurn narrate(
            Campaign campaign, GameSession session, Campaign.Room room, PromptContext context) {
        GameMasterTurn fallback = fallbackTurn(room, context.extra());
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
                                                    userPrompt(campaign, session, room, context))),
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
        JsonNode node = readGmJson(json);
        if (node != null) {
            GameMasterTurn turn = new GameMasterTurn();
            turn.narrative = text(node, "narrative", room.narrative);
            turn.puzzleType = text(node, "puzzle_type", room.puzzleType);
            turn.expectedCommandPattern = room.expectedCommandPattern;
            turn.hint = text(node, "hint", room.hint);
            turn.canvasEvent = text(node, "canvas_event", "focus_room");
            turn.yamlFallback = false;
            return turn;
        }
        LOG.debug("Could not parse Game Master JSON; using narrative only");
        GameMasterTurn turn = fallbackTurn(room, null);
        String prose = extractNarrativeField(content);
        if (prose != null) {
            turn.narrative = prose;
        } else if (!looksLikeGmJson(content)) {
            turn.narrative = content;
        }
        turn.yamlFallback = false;
        return turn;
    }

    private JsonNode readGmJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ignored) {
            try {
                // Granite copies YAML regexes and emits invalid JSON escapes such as \$.
                return mapper.readTree(json.replace("\\$", "$"));
            } catch (Exception e) {
                return null;
            }
        }
    }

    static String extractNarrativeField(String content) {
        if (content == null) {
            return null;
        }
        String key = "\"narrative\"";
        int keyAt = content.indexOf(key);
        if (keyAt < 0) {
            return null;
        }
        int colon = content.indexOf(':', keyAt + key.length());
        int quote = content.indexOf(JSON_QUOTE, colon + 1);
        if (colon < 0 || quote < 0) {
            return null;
        }
        StringBuilder prose = new StringBuilder();
        int i = quote + 1;
        while (i < content.length()) {
            char c = content.charAt(i);
            i++;
            if (c == JSON_ESCAPE && i < content.length()) {
                prose.append(unescapeJson(content.charAt(i)));
                i++;
                continue;
            }
            if (c == JSON_QUOTE) {
                String trimmed = prose.toString().trim();
                return trimmed.isEmpty() ? null : trimmed;
            }
            prose.append(c);
        }
        return null;
    }

    private static char unescapeJson(char next) {
        return switch (next) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            default -> next;
        };
    }

    static boolean looksLikeGmJson(String content) {
        String src = content.strip();
        return src.startsWith("{")
                && (src.contains("\"puzzle_type\"")
                        || src.contains("\"expected_command_pattern\"")
                        || src.contains("\"canvas_event\""));
    }

    private GameMasterTurn fallbackTurn(Campaign.Room room, String extra) {
        GameMasterTurn turn = new GameMasterTurn();
        turn.narrative =
                extra == null || extra.isBlank() ? room.narrative : extra + "\n\n" + room.narrative;
        turn.puzzleType = room.puzzleType;
        turn.expectedCommandPattern = room.expectedCommandPattern;
        turn.hint = room.hint;
        turn.canvasEvent = "focus_room";
        turn.yamlFallback = true;
        return turn;
    }

    String userPrompt(
            Campaign campaign, GameSession session, Campaign.Room room, PromptContext context) {
        boolean sceneBeat = context.yamlPassed() == null;
        String intended = context.intendedExample();
        if (sceneBeat) {
            intended = "(withheld — scene beat, not a scored attempt)";
        } else if (intended == null || intended.isBlank()) {
            intended = firstAcceptedExample(room);
        }
        String submission =
                context.playerSubmission() == null || context.playerSubmission().isBlank()
                        ? "(none — this is a scene beat)"
                        : context.playerSubmission();
        String yamlScore =
                sceneBeat
                        ? "none (scene beat, not a scored attempt)"
                        : (context.yamlPassed() ? "passed" : "failed");
        return """
                Campaign: %s
                Elapsed seconds: %d
                Current room: %s (%s)
                Puzzle type: %s
                Room prompt: %s
                Authored narrative: %s
                Expected command pattern (do not rewrite, do not dump in narrative): %s
                Intended accepted example (private coaching; never quote in narrative unless Extra says they asked for a hint after a fail):
                %s
                Inventory: %s
                YAML scorer result: %s
                Speaker: %s
                Player submission:
                %s
                Extra:
                %s

                Stay in Game Master voice.
                YAML already scored; you narrate only.
                Address the speaker by alias when Speaker is a name. When Speaker says the beat is to the room, do not attribute it to a player. Do not invent a traveler. Do not rewrite the expected command pattern.
                %s

                Return JSON only:
                {"narrative":"...","puzzle_type":"%s","expected_command_pattern":"%s","hint":"...","canvas_event":"focus_room"}
                """
                .formatted(
                        fmtArg(campaign.metadata.title),
                        session.elapsedSeconds,
                        fmtArg(room.title),
                        fmtArg(room.id),
                        fmtArg(room.puzzleType),
                        fmtArg(room.prompt),
                        fmtArg(room.narrative),
                        fmtArg(room.expectedCommandPattern),
                        fmtArg(intended),
                        fmtArg(String.valueOf(session.inventory)),
                        yamlScore,
                        fmtArg(speakerLabel(context)),
                        fmtArg(submission),
                        fmtArg(context.extra() == null ? "" : context.extra()),
                        coaching(context.yamlPassed()),
                        fmtArg(room.puzzleType),
                        fmtArg(room.expectedCommandPattern).replace("\"", "\\\""));
    }

    private static String speakerLabel(PromptContext context) {
        if (context.yamlPassed() == null) {
            return "(none — this scene beat is to the room)";
        }
        String alias = context.speakerAlias();
        if (alias == null || alias.isBlank()) {
            return "(none)";
        }
        return alias.strip();
    }

    public static String firstAcceptedExample(Campaign.Room room) {
        if (room == null || room.acceptedExamples == null || room.acceptedExamples.isEmpty()) {
            return "(none authored)";
        }
        String example = room.acceptedExamples.getFirst();
        return example == null || example.isBlank() ? "(none authored)" : example.strip();
    }

    private static String coaching(Boolean yamlPassed) {
        if (yamlPassed == null) {
            return "This is a scene beat for the CURRENT room, not a scored attempt. If Extra says the previous room was solved, acknowledge it in one clause, then set THIS room's scene from the authored narrative. There is no player submission against this puzzle. Do not critique Extra as a wrong command. Do not dump this room's intended example or regex.";
        }
        if (yamlPassed) {
            return "YAML scorer passed. Celebrate the submission in Game Master voice. Do not quote the intended example or regex.";
        }
        return "Give feedback about the player submission. If it is chatter, a greeting, or not a command / YAML / oc / Java snippet that could solve this room, tell them you need a solving command. YAML scorer failed. Do not quote the intended example.";
    }

    private static String fmtArg(String value) {
        return value == null ? "" : value.replace("%", "%%");
    }

    record PromptContext(
            String extra,
            String playerSubmission,
            Boolean yamlPassed,
            String intendedExample,
            String speakerAlias) {

        static PromptContext scene(String extra) {
            return new PromptContext(extra, null, null, null, null);
        }

        static PromptContext attempt(
                String extra,
                String playerSubmission,
                boolean yamlPassed,
                String intendedExample,
                String speakerAlias) {
            return new PromptContext(
                    extra, playerSubmission, yamlPassed, intendedExample, speakerAlias);
        }
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
        public boolean yamlFallback;
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
