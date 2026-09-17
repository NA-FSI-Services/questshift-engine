package io.questshift.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.questshift.campaign.Campaign;
import io.questshift.session.GameSession.AdventureSummary;
import io.questshift.session.GameSession.CommandLogEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdventureSummarizerTest {

    @Test
    void looksLikeCommandDetectsOpsAndSkipsChatter() {
        assertTrue(AdventureSummarizer.looksLikeCommand("oc get pods"));
        assertTrue(AdventureSummarizer.looksLikeCommand("cat /var/log/quest.log"));
        assertTrue(AdventureSummarizer.looksLikeCommand("grep -i rune /var/log/quest.log"));
        assertTrue(AdventureSummarizer.looksLikeCommand("- hosts: dungeon\n  gather_facts: true"));
        assertTrue(
                AdventureSummarizer.looksLikeCommand(
                        "@Path(\"/hello\") public class HelloResource"));
        assertFalse(AdventureSummarizer.looksLikeCommand("Hello"));
        assertFalse(AdventureSummarizer.looksLikeCommand("hint please"));
        assertFalse(AdventureSummarizer.looksLikeCommand(null));
    }

    @Test
    void joinNamesFormatsTies() {
        assertEquals("", AdventureSummarizer.joinNames(List.of()));
        assertEquals("Ada", AdventureSummarizer.joinNames(List.of("Ada")));
        assertEquals("Ada and Briar", AdventureSummarizer.joinNames(List.of("Ada", "Briar")));
        assertEquals(
                "Ada, Briar, and Linus",
                AdventureSummarizer.joinNames(List.of("Ada", "Briar", "Linus")));
    }

    @Test
    void summarizeSplitsQuestionsCommandsAndStageClears() {
        Campaign campaign = new Campaign();
        Campaign.Room shell = room("room-01-broken-shell", "The Broken Shell");
        Campaign.Room playbook = room("room-02-playbook-of-binding", "The Playbook of Binding");
        campaign.rooms.add(shell);
        campaign.rooms.add(playbook);
        GameSession session = new GameSession();
        session.commandLog.add(entry("room-01-broken-shell", "Ada", "Hello", false));
        session.commandLog.add(entry("room-01-broken-shell", "Ada", "hint", false));
        session.commandLog.add(
                entry(
                        "room-01-broken-shell",
                        "Ada",
                        "grep -i rune /var/log/quest.log | awk '{print $NF}'",
                        true));
        session.commandLog.add(
                entry("room-02-playbook-of-binding", "Linus", "cat /var/log/quest.log", false));
        session.commandLog.add(
                entry("room-02-playbook-of-binding", "Linus", "- hosts: dungeon", true));
        AdventureSummary summary = AdventureSummarizer.summarize(campaign, session);
        assertEquals("Ada", summary.mostQuestions);
        assertEquals(2, summary.mostQuestionsCount);
        assertEquals("Linus", summary.mostCommands);
        assertEquals(2, summary.mostCommandsCount);
        assertEquals(2, summary.stages.size());
        assertEquals("The Broken Shell", summary.stages.getFirst().roomTitle);
        assertEquals("Ada", summary.stages.getFirst().name);
        assertEquals("Linus", summary.stages.get(1).name);
        assertTrue(summary.prose.contains("Ada asked the most questions (2)"));
        assertTrue(summary.prose.contains("Linus tried the most commands (2)"));
        assertTrue(summary.prose.contains("The Broken Shell — Ada"));
        assertTrue(summary.prose.contains("The Playbook of Binding — Linus"));
    }

    @Test
    void summarizeTiesAndEmptyLog() {
        AdventureSummary empty = AdventureSummarizer.summarize(null, new GameSession());
        assertEquals("", empty.mostQuestions);
        assertEquals(0, empty.mostQuestionsCount);
        assertTrue(empty.prose.contains("Nobody asked the Game Master a question"));
        GameSession session = new GameSession();
        session.commandLog.add(entry("room-01-broken-shell", "Ada", "Hello", false));
        session.commandLog.add(entry("room-01-broken-shell", "Briar", "hint", false));
        AdventureSummary tied = AdventureSummarizer.summarize(null, session);
        assertEquals("Ada and Briar", tied.mostQuestions);
        assertEquals(1, tied.mostQuestionsCount);
        assertTrue(tied.prose.contains("Ada and Briar asked the most questions (1)"));
        assertTrue(tied.prose.contains("Nobody tried a command"));
    }

    @Test
    void passedSnippetCountsAsCommandEvenWithoutTokens() {
        GameSession session = new GameSession();
        session.commandLog.add(
                entry("room-05-operators-throne", "Moss", "THORN-ASH-OAK-IRON", true));
        AdventureSummary summary = AdventureSummarizer.summarize(null, session);
        assertEquals("Moss", summary.mostCommands);
        assertEquals(1, summary.mostCommandsCount);
        assertEquals(0, summary.mostQuestionsCount);
    }

    private static Campaign.Room room(String id, String title) {
        Campaign.Room room = new Campaign.Room();
        room.id = id;
        room.title = title;
        return room;
    }

    private static CommandLogEntry entry(
            String roomId, String name, String command, boolean passed) {
        CommandLogEntry entry = new CommandLogEntry();
        entry.roomId = roomId;
        entry.name = name;
        entry.command = command;
        entry.passed = passed;
        return entry;
    }
}
