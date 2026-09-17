package io.questshift.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class GameSessionTest {

    @Test
    void tickElapsedCountsSecondsSinceStart() {
        GameSession session = new GameSession();
        session.startedAt = Instant.now().minusSeconds(12);
        session.tickElapsed();
        assertTrue(session.elapsedSeconds >= 12);
    }

    @Test
    void defaultPartyFieldsAreEmptyCollections() {
        GameSession session = new GameSession();
        assertEquals("active", session.status);
        assertTrue(session.inventory.isEmpty());
        assertTrue(session.puzzleCompletion.isEmpty());
        assertTrue(session.foundClues.isEmpty());
    }

    @Test
    void partyMemberStoresNameAndSeat() {
        GameSession.PartyMember member = new GameSession.PartyMember("Ada", "guardian");
        assertEquals("Ada", member.name);
        assertEquals("guardian", member.seatId);
        assertTrue(member.foundClues.isEmpty());
    }
}
