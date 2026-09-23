package io.questshift.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TurnRulesTest {

    @Test
    void openingGrantsFirstStartAlias() {
        assertEquals(
                "Ada",
                TurnRules.openingHolder(
                        List.of(
                                new GameSession.PartyMember("Ada", "guardian"),
                                new GameSession.PartyMember("Linus", "automancer"))));
    }

    @Test
    void blankAndSharedNeverHoldTheFloor() {
        GameSession session = new GameSession();
        session.turnName = "Ada";
        session.partyMembers.add(new GameSession.PartyMember("Ada", "guardian"));
        assertFalse(TurnRules.holdsFloor(session, ""));
        assertFalse(TurnRules.holdsFloor(session, "shared"));
        assertFalse(TurnRules.holdsFloor(session, "Linus"));
        assertTrue(TurnRules.holdsFloor(session, "ada"));
    }

    @Test
    void soloKeepsTheFloorOnRotate() {
        GameSession session = new GameSession();
        session.turnName = "Ada";
        session.partyMembers.add(new GameSession.PartyMember("Ada", "guardian"));
        assertEquals("Ada", TurnRules.rotate(session));
    }

    @Test
    void rotateWalksJoinOrderCircular() {
        GameSession session = new GameSession();
        session.turnName = "Ada";
        session.partyMembers.add(new GameSession.PartyMember("Ada", "guardian"));
        session.partyMembers.add(new GameSession.PartyMember("Linus", "automancer"));
        session.partyMembers.add(new GameSession.PartyMember("Briar", "ranger"));
        assertEquals("Linus", TurnRules.rotate(session));
        session.turnName = "Linus";
        assertEquals("Briar", TurnRules.rotate(session));
        session.turnName = "Briar";
        assertEquals("Ada", TurnRules.rotate(session));
    }

    @Test
    void leaveOfHolderGrantsNextRemaining() {
        List<GameSession.PartyMember> before =
                List.of(
                        new GameSession.PartyMember("Ada", "guardian"),
                        new GameSession.PartyMember("Linus", "automancer"),
                        new GameSession.PartyMember("Briar", "ranger"));
        List<GameSession.PartyMember> after =
                List.of(
                        new GameSession.PartyMember("Linus", "automancer"),
                        new GameSession.PartyMember("Briar", "ranger"));
        assertEquals("Linus", TurnRules.afterLeave(before, after, "Ada"));
    }

    @Test
    void restoreKeepsTurnNameWhenStillInParty() {
        GameSession session = new GameSession();
        session.turnName = "Linus";
        session.partyMembers.add(new GameSession.PartyMember("Ada", "guardian"));
        session.partyMembers.add(new GameSession.PartyMember("Linus", "automancer"));
        assertEquals("Linus", TurnRules.restore(session));
        session.turnName = "Ghost";
        assertEquals("Ada", TurnRules.restore(session));
    }

    @Test
    void withGrantAppendsFloorLine() {
        assertEquals(
                "Torchlight. Ada, the floor is yours.", TurnRules.withGrant("Torchlight.", "Ada"));
        assertEquals(
                "Torchlight. Ada, the floor is yours.",
                TurnRules.withGrant("Torchlight. Ada, the floor is yours.", "Ada"));
    }
}
