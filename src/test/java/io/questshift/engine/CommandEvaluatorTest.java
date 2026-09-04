package io.questshift.engine;

import io.questshift.campaign.Campaign;
import io.questshift.session.GameSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandEvaluatorTest {

    private final CommandEvaluator evaluator = new CommandEvaluator();

    @Test
    void linuxPipelinePasses() {
        Campaign.Room room = linuxRoom();
        GameSession session = new GameSession();
        var result = evaluator.evaluate(session, room, "cat /var/log/quest.log | grep -i rune | awk '{print $NF}'");
        assertTrue(result.passed());
    }

    @Test
    void linuxCatOnlyFails() {
        Campaign.Room room = linuxRoom();
        var result = evaluator.evaluate(new GameSession(), room, "cat /var/log/quest.log");
        assertFalse(result.passed());
    }

    @Test
    void javaSnippetPasses() {
        Campaign.Room room = new Campaign.Room();
        room.puzzleType = "java";
        room.expectedCommandPattern = "(?s).*@Path\\(\"/hello\"\\).*QuestShift lives.*";
        room.forbiddenPatterns = java.util.List.of("greeting\\.toUpperCase");
        String snippet = """
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
        room.expectedCommandPattern = ".*oc\\s+annotate\\s+namespace\\s+dungeon\\s+questshift/name=thorn-ash-oak-iron.*";
        room.requiresLoot = java.util.List.of("rune-thorn", "rune-ash", "rune-oak", "rune-iron");
        GameSession poor = new GameSession();
        assertFalse(evaluator.evaluate(poor, room, "oc annotate namespace dungeon questshift/name=thorn-ash-oak-iron").passed());
        GameSession rich = new GameSession();
        rich.inventory.addAll(room.requiresLoot);
        assertTrue(evaluator.evaluate(rich, room, "oc annotate namespace dungeon questshift/name=thorn-ash-oak-iron").passed());
    }

    private Campaign.Room linuxRoom() {
        Campaign.Room room = new Campaign.Room();
        room.puzzleType = "linux";
        room.expectedCommandPattern = "(?s).*grep.*rune.*awk.*";
        room.forbiddenPatterns = java.util.List.of("^\\s*cat\\s+/var/log/quest\\.log\\s*$");
        room.acceptedExamples = java.util.List.of("cat /var/log/quest.log | grep -i rune | awk '{print $NF}'");
        return room;
    }
}
