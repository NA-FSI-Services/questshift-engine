package io.questshift.session;

import io.questshift.campaign.Campaign;
import io.questshift.session.GameSession.AdventureSummary;
import io.questshift.session.GameSession.CommandLogEntry;
import io.questshift.session.GameSession.StageClear;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Recap who talked to the GM, who tried commands, and who cleared each room. */
public final class AdventureSummarizer {

    private static final Pattern COMMANDISH =
            Pattern.compile(
                    "(?is).*(\\boc\\b|\\bkubectl\\b|\\bgrep\\b|\\bawk\\b|\\bcat\\b|hosts:\\s|"
                            + "gather_facts|@Path|public\\s+class|/healthz|/hello|questshift/name|"
                            + "ansible).*");

    private static final int SOLO_NAME = 1;
    private static final int PAIR_NAMES = 2;

    private AdventureSummarizer() {}

    public static AdventureSummary summarize(Campaign campaign, GameSession session) {
        AdventureSummary summary = new AdventureSummary();
        List<CommandLogEntry> log =
                session == null || session.commandLog == null ? List.of() : session.commandLog;
        Map<String, Integer> questions = new HashMap<>();
        Map<String, Integer> commands = new HashMap<>();
        for (CommandLogEntry entry : log) {
            String who = alias(entry);
            if (looksLikeCommand(entry.command) || entry.passed) {
                commands.merge(who, 1, Integer::sum);
            } else {
                questions.merge(who, 1, Integer::sum);
            }
        }
        Leaders questionLeaders = leaders(questions);
        summary.mostQuestions = questionLeaders.names;
        summary.mostQuestionsCount = questionLeaders.count;
        Leaders commandLeaders = leaders(commands);
        summary.mostCommands = commandLeaders.names;
        summary.mostCommandsCount = commandLeaders.count;
        if (campaign != null && campaign.rooms != null) {
            for (Campaign.Room room : campaign.rooms) {
                String clearer = firstPasser(log, room.id);
                if (clearer == null) {
                    continue;
                }
                StageClear stage = new StageClear();
                stage.roomId = room.id;
                stage.roomTitle = room.title == null || room.title.isBlank() ? room.id : room.title;
                stage.name = clearer;
                summary.stages.add(stage);
            }
        }
        summary.prose = prose(summary);
        return summary;
    }

    static boolean looksLikeCommand(String command) {
        return command != null && COMMANDISH.matcher(command).matches();
    }

    private static String firstPasser(List<CommandLogEntry> log, String roomId) {
        for (CommandLogEntry entry : log) {
            if (entry.passed && roomId.equals(entry.roomId)) {
                return alias(entry);
            }
        }
        return null;
    }

    private static String alias(CommandLogEntry entry) {
        if (entry == null || entry.name == null || entry.name.isBlank()) {
            return "someone";
        }
        return entry.name.trim();
    }

    private static Leaders leaders(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return new Leaders("", 0);
        }
        int best = counts.values().stream().max(Integer::compareTo).orElse(0);
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == best) {
                names.add(entry.getKey());
            }
        }
        names.sort(Comparator.naturalOrder());
        return new Leaders(joinNames(names), best);
    }

    static String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        if (names.size() == SOLO_NAME) {
            return names.getFirst();
        }
        if (names.size() == PAIR_NAMES) {
            return names.get(0) + " and " + names.get(1);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + ", and " + names.getLast();
    }

    private static String prose(AdventureSummary summary) {
        StringBuilder text = new StringBuilder("The hour is complete.");
        if (summary.mostQuestionsCount <= 0) {
            text.append("\nNobody asked the Game Master a question.");
        } else {
            text.append('\n')
                    .append(summary.mostQuestions)
                    .append(" asked the most questions (")
                    .append(summary.mostQuestionsCount)
                    .append(").");
        }
        if (summary.mostCommandsCount <= 0) {
            text.append("\nNobody tried a command.");
        } else {
            text.append('\n')
                    .append(summary.mostCommands)
                    .append(" tried the most commands (")
                    .append(summary.mostCommandsCount)
                    .append(").");
        }
        if (!summary.stages.isEmpty()) {
            text.append("\nWho cleared each stage:");
            for (StageClear stage : summary.stages) {
                text.append("\n- ").append(stage.roomTitle).append(" — ").append(stage.name);
            }
        }
        return text.toString();
    }

    private record Leaders(String names, int count) {}
}
