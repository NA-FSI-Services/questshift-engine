package io.questshift.engine;

import io.questshift.campaign.Campaign;
import io.questshift.session.GameSession;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class CommandEvaluator {

    public Evaluation evaluate(GameSession session, Campaign.Room room, String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.strip();
        if (command.isEmpty()) {
            return Evaluation.fail("The terminal waits. Type a command.");
        }
        if (room.requiresLoot != null) {
            for (String lootId : room.requiresLoot) {
                if (!session.inventory.contains(lootId)) {
                    return Evaluation.fail("The throne rejects you. Missing loot: " + lootId);
                }
            }
        }
        if (matchesAny(room.forbiddenPatterns, command)) {
            return Evaluation.fail("The dungeon knows that trick. It is the cursed form.");
        }
        if (matchesPattern(room.expectedCommandPattern, command)
                || matchesExample(room.acceptedExamples, command)) {
            return Evaluation.pass("The dungeon accepts the command.");
        }
        if (softMatch(room.puzzleType, command)) {
            return Evaluation.pass("Close enough. The pattern holds.");
        }
        return Evaluation.fail("Nothing happens. The pattern does not bind.");
    }

    private boolean matchesPattern(String regex, String command) {
        if (regex == null || regex.isBlank()) {
            return false;
        }
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(command)
                    .find();
        } catch (PatternSyntaxException e) {
            return command.toLowerCase(Locale.ROOT).contains(regex.toLowerCase(Locale.ROOT));
        }
    }

    private boolean matchesAny(List<String> patterns, String command) {
        if (patterns == null) {
            return false;
        }
        return patterns.stream().anyMatch(p -> matchesPattern(p, command));
    }

    private boolean matchesExample(List<String> examples, String command) {
        if (examples == null) {
            return false;
        }
        String normalized = collapse(command);
        return examples.stream().map(this::collapse).anyMatch(normalized::equals);
    }

    private boolean softMatch(String puzzleType, String command) {
        String c = command.toLowerCase(Locale.ROOT);
        if (puzzleType == null) {
            return false;
        }
        return switch (puzzleType) {
            case "linux" -> c.contains("grep") && c.contains("rune") && c.contains("awk");
            case "ansible" ->
                    c.contains("hosts:")
                            && c.contains("dungeon")
                            && c.contains("gather_facts")
                            && c.contains("/etc/questshift/name")
                            && c.contains("name:");
            case "openshift" ->
                    (c.contains("oc ") || c.contains("kubectl "))
                            && (c.contains("set probe")
                                            && c.contains("/healthz")
                                            && c.contains("dungeon")
                                    || c.contains("annotate") && c.contains("thorn-ash-oak-iron"));
            case "java" ->
                    c.contains("@path(\"/hello\")")
                            && c.contains("@get")
                            && c.contains("questshift lives")
                            && !c.contains("greeting.touppercase");
            default -> false;
        };
    }

    private String collapse(String value) {
        return value.replace("\r\n", "\n").replace('\t', ' ').strip().replaceAll(" +", " ");
    }

    public record Evaluation(boolean passed, String message) {
        public static Evaluation pass(String message) {
            return new Evaluation(true, message);
        }

        public static Evaluation fail(String message) {
            return new Evaluation(false, message);
        }
    }
}
