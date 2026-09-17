package io.questshift.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.questshift.campaign.Campaign;
import io.questshift.session.GameSession;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class CommandEvaluatorTest {

    private final CommandEvaluator evaluator = new CommandEvaluator();
    private final Campaign campaign = loadCampaign();

    @Test
    void linuxPipelinePasses() {
        Campaign.Room room = linuxRoom();
        GameSession session = new GameSession();
        var result =
                evaluator.evaluate(
                        session, room, "cat /var/log/quest.log | grep -i rune | awk '{print $NF}'");
        assertTrue(result.passed());
    }

    @Test
    void linuxCatOnlyFails() {
        Campaign.Room room = linuxRoom();
        var result = evaluator.evaluate(new GameSession(), room, "cat /var/log/quest.log");
        assertFalse(result.passed());
    }

    @Test
    void linuxNameOnlyIsAnAuthoredGolemMiss() {
        Campaign.Room room = campaign.roomById("room-01-broken-shell");
        assertNotNull(room);
        var result = evaluator.evaluate(new GameSession(), room, "THORN");
        assertFalse(result.passed());
        assertTrue(result.authoredMiss());
        assertTrue(result.message().toLowerCase(java.util.Locale.ROOT).contains("filesystem"));
        var alias = evaluator.evaluate(new GameSession(), room, "rune=THORN");
        assertTrue(alias.authoredMiss());
    }

    @Test
    void linuxPartialGrepIsAnAuthoredGolemMiss() {
        Campaign.Room room = campaign.roomById("room-01-broken-shell");
        assertNotNull(room);
        var result = evaluator.evaluate(new GameSession(), room, "grep -i rune /var/log/quest.log");
        assertFalse(result.passed());
        assertTrue(result.authoredMiss());
        assertTrue(result.message().toLowerCase(java.util.Locale.ROOT).contains("too long"));
    }

    @Test
    void javaSnippetPasses() {
        Campaign.Room room = new Campaign.Room();
        room.puzzleType = "java";
        room.expectedCommandPattern = "(?s).*@Path\\(\"/hello\"\\).*QuestShift lives.*";
        room.forbiddenPatterns = java.util.List.of("greeting\\.toUpperCase");
        String snippet =
                """
                @Path("/hello")
                public class HelloResource {
                    @GET
                    @Produces(MediaType.TEXT_PLAIN)
                    public String hello() {
                        return "QuestShift lives";
                    }
                }
                """;
        assertTrue(evaluator.evaluate(new GameSession(), room, snippet).passed());
    }

    @Test
    void bossRequiresRunes() {
        Campaign.Room room = new Campaign.Room();
        room.puzzleType = "openshift";
        room.expectedCommandPattern =
                ".*oc\\s+annotate\\s+namespace\\s+dungeon\\s+questshift/name=thorn-ash-oak-iron.*";
        room.requiresLoot = java.util.List.of("rune-thorn", "rune-ash", "rune-oak", "rune-iron");
        GameSession poor = new GameSession();
        assertFalse(
                evaluator
                        .evaluate(
                                poor,
                                room,
                                "oc annotate namespace dungeon questshift/name=thorn-ash-oak-iron")
                        .passed());
        GameSession rich = new GameSession();
        rich.inventory.addAll(room.requiresLoot);
        assertTrue(
                evaluator
                        .evaluate(
                                rich,
                                room,
                                "oc annotate namespace dungeon questshift/name=thorn-ash-oak-iron")
                        .passed());
    }

    @Test
    void yamlAcceptedExamplesPassAndForbiddenFail() {
        GameSession session = new GameSession();
        for (Campaign.Room room : campaign.rooms) {
            if (room.requiresLoot != null) {
                session.inventory.addAll(room.requiresLoot);
            }
            for (String example : room.acceptedExamples) {
                assertTrue(
                        evaluator.evaluate(session, room, example).passed(),
                        () -> room.id + " should accept:\n" + example);
            }
            for (String forbidden : room.forbiddenPatterns) {
                String cursed =
                        switch (room.id) {
                            case "room-01-broken-shell" -> "cat /var/log/quest.log";
                            case "room-03-pod-that-would-not-wake" ->
                                    "oc set probe pod/crashing-wizard --liveness --get-url=http://:8080/readyz -n dungeon";
                            case "room-04-cursed-servlet" ->
                                    """
                            @Path("/helo")
                            public class HelloResource {
                                String greeting;
                                @GET
                                public String hello() {
                                    return greeting.toUpperCase();
                                }
                            }
                            """;
                            default -> forbidden;
                        };
                assertFalse(
                        evaluator.evaluate(session, room, cursed).passed(),
                        () -> room.id + " should reject cursed form");
            }
        }
    }

    @Test
    void emptyCommandFails() {
        assertFalse(evaluator.evaluate(new GameSession(), linuxRoom(), "   ").passed());
        assertFalse(evaluator.evaluate(new GameSession(), linuxRoom(), null).passed());
    }

    @Test
    void invalidRegexFallsBackToSubstring() {
        Campaign.Room room = new Campaign.Room();
        room.puzzleType = "custom";
        room.expectedCommandPattern = "(unclosed";
        assertTrue(evaluator.evaluate(new GameSession(), room, "(unclosed").passed());
        assertFalse(evaluator.evaluate(new GameSession(), room, "other").passed());
    }

    @Test
    void unknownPuzzleTypeDoesNotSoftMatch() {
        Campaign.Room room = new Campaign.Room();
        room.puzzleType = "riddle";
        assertFalse(evaluator.evaluate(new GameSession(), room, "anything").passed());
        room.puzzleType = null;
        assertFalse(evaluator.evaluate(new GameSession(), room, "grep rune awk").passed());
    }

    @Test
    void acceptedExampleMatchesWhenRegexMissing() {
        Campaign.Room room = new Campaign.Room();
        room.puzzleType = "linux";
        room.acceptedExamples = java.util.List.of("echo rune");
        assertTrue(evaluator.evaluate(new GameSession(), room, "echo rune").passed());
    }

    @Test
    void unmatchedCommandFails() {
        assertFalse(evaluator.evaluate(new GameSession(), linuxRoom(), "echo hello").passed());
    }

    @Test
    void softMatchCoversPuzzleTypes() {
        CommandEvaluator scorer = evaluator;
        Campaign.Room linux = new Campaign.Room();
        linux.puzzleType = "linux";
        assertTrue(scorer.evaluate(new GameSession(), linux, "grep rune | awk").passed());
        Campaign.Room ansible = new Campaign.Room();
        ansible.puzzleType = "ansible";
        assertTrue(
                scorer.evaluate(
                                new GameSession(),
                                ansible,
                                "hosts: dungeon\ngather_facts: true\nname: bind\ncopy:\n  dest: /etc/questshift/name\n")
                        .passed());
        Campaign.Room oc = new Campaign.Room();
        oc.puzzleType = "openshift";
        assertTrue(
                scorer.evaluate(
                                new GameSession(),
                                oc,
                                "oc set probe pod/x --liveness --get-url=http://:8080/healthz -n dungeon")
                        .passed());
        assertTrue(
                scorer.evaluate(
                                new GameSession(),
                                oc,
                                "kubectl annotate namespace dungeon questshift/name=thorn-ash-oak-iron")
                        .passed());
        Campaign.Room java = new Campaign.Room();
        java.puzzleType = "java";
        assertTrue(
                scorer.evaluate(new GameSession(), java, "@Path(\"/hello\") @GET QuestShift lives")
                        .passed());
    }

    private static Campaign loadCampaign() {
        try (InputStream in =
                Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream("campaigns/campaign-devops-dungeon.yaml")) {
            assertNotNull(in, "classpath campaign missing");
            return new ObjectMapper(new YAMLFactory()).readValue(in, Campaign.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Campaign.Room linuxRoom() {
        Campaign.Room room = new Campaign.Room();
        room.puzzleType = "linux";
        room.expectedCommandPattern = "(?s).*grep.*rune.*awk.*";
        room.forbiddenPatterns = java.util.List.of("^\\s*cat\\s+/var/log/quest\\.log\\s*$");
        room.acceptedExamples =
                java.util.List.of("cat /var/log/quest.log | grep -i rune | awk '{print $NF}'");
        return room;
    }
}
