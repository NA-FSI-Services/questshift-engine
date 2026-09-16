package io.questshift.session;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Cosmetic seats, unique aliases, max eight players. */
public final class PartyRules {

    public static final int MAX_MEMBERS = 8;

    public static final int MAX_ALIAS_LENGTH = 32;

    static final Set<String> SEAT_IDS = Set.of("guardian", "automancer", "ranger", "artificer");

    private PartyRules() {}

    public static String normalizeAlias(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public static GameSession.PartyMember requireMember(GameSession.PartyMember raw) {
        if (raw == null || raw.name == null || raw.name.isBlank()) {
            throw new PartyInvalidException("Alias is required.");
        }
        String seatId = raw.seatId == null ? "" : raw.seatId.trim();
        if (!SEAT_IDS.contains(seatId)) {
            throw new PartyInvalidException("Unknown seat: " + raw.seatId);
        }
        String name = raw.name.trim();
        if (name.length() > MAX_ALIAS_LENGTH) {
            throw new PartyInvalidException("Alias is too long.");
        }
        return new GameSession.PartyMember(name, seatId);
    }

    public static List<GameSession.PartyMember> requireOpeningParty(
            List<GameSession.PartyMember> party) {
        if (party == null || party.isEmpty()) {
            throw new PartyInvalidException("Start requires a character and a unique alias.");
        }
        if (party.size() > MAX_MEMBERS) {
            throw new PartyConflictException("party_full", "The party is full (8).");
        }
        List<GameSession.PartyMember> members = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GameSession.PartyMember raw : party) {
            GameSession.PartyMember member = requireMember(raw);
            if (!seen.add(normalizeAlias(member.name))) {
                throw new PartyConflictException(
                        "alias_taken", "Alias already in the party: " + member.name);
            }
            members.add(member);
        }
        return members;
    }
}
