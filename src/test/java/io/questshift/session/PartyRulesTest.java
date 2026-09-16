package io.questshift.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PartyRulesTest {

    @Test
    void openingPartyRequiresAMember() {
        assertThrows(PartyInvalidException.class, () -> PartyRules.requireOpeningParty(List.of()));
        assertThrows(PartyInvalidException.class, () -> PartyRules.requireOpeningParty(null));
    }

    @Test
    void memberNeedsAliasAndKnownSeat() {
        assertThrows(PartyInvalidException.class, () -> PartyRules.requireMember(null));
        GameSession.PartyMember blank = new GameSession.PartyMember("  ", "guardian");
        assertThrows(PartyInvalidException.class, () -> PartyRules.requireMember(blank));
        GameSession.PartyMember badSeat = new GameSession.PartyMember("Ada", "wizard");
        assertThrows(PartyInvalidException.class, () -> PartyRules.requireMember(badSeat));
        GameSession.PartyMember ok =
                PartyRules.requireMember(new GameSession.PartyMember(" Ada ", "guardian"));
        assertEquals("Ada", ok.name);
        assertEquals("guardian", ok.seatId);
        GameSession.PartyMember tooLong =
                new GameSession.PartyMember(
                        "a".repeat(PartyRules.MAX_ALIAS_LENGTH + 1), "guardian");
        assertThrows(PartyInvalidException.class, () -> PartyRules.requireMember(tooLong));
    }

    @Test
    void openingPartyRejectsDuplicateAliases() {
        assertThrows(
                PartyConflictException.class,
                () ->
                        PartyRules.requireOpeningParty(
                                List.of(
                                        new GameSession.PartyMember("Ada", "guardian"),
                                        new GameSession.PartyMember("ada", "ranger"))));
    }
}
