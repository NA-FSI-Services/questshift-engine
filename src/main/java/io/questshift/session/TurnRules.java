package io.questshift.session;

import java.util.List;

/**
 * Game Master floor. Join order only — seats stay cosmetic. The LLM must not set {@code turnName}.
 */
public final class TurnRules {

    public static final String SHARED = "shared";

    private static final int SOLO_PARTY = 1;

    private TurnRules() {}

    public static String grantLine(String holder) {
        if (holder == null || holder.isBlank()) {
            return "";
        }
        return holder.trim() + ", the floor is yours.";
    }

    public static String withGrant(String narrative, String holder) {
        String grant = grantLine(holder);
        if (grant.isEmpty()) {
            return narrative == null ? "" : narrative;
        }
        String base = narrative == null ? "" : narrative.strip();
        if (base.isEmpty()) {
            return grant;
        }
        if (base.contains(grant)) {
            return base;
        }
        return base + " " + grant;
    }

    public static boolean holdsFloor(GameSession session, String alias) {
        if (session == null || session.turnName == null || session.turnName.isBlank()) {
            return false;
        }
        String speaker = alias == null ? "" : alias.trim();
        if (speaker.isEmpty() || SHARED.equalsIgnoreCase(speaker)) {
            return false;
        }
        return PartyRules.normalizeAlias(session.turnName)
                .equals(PartyRules.normalizeAlias(speaker));
    }

    /** First Start alias, or empty when the party is empty. */
    public static String openingHolder(List<GameSession.PartyMember> party) {
        if (party == null || party.isEmpty()) {
            return "";
        }
        GameSession.PartyMember first = party.getFirst();
        return first == null || first.name == null ? "" : first.name.trim();
    }

    /**
     * After a scored attempt: next join-order alias (circular). Solo keeps the floor. Empty party
     * clears the floor.
     */
    public static String rotate(GameSession session) {
        if (session == null || session.partyMembers == null || session.partyMembers.isEmpty()) {
            return "";
        }
        List<GameSession.PartyMember> party = session.partyMembers;
        if (party.size() == SOLO_PARTY) {
            return openingHolder(party);
        }
        int index = indexOf(party, session.turnName);
        int next = index < 0 ? 0 : (index + 1) % party.size();
        return party.get(next).name.trim();
    }

    /**
     * When the floor holder leaves, grant the next remaining member from join order. {@code
     * beforeLeave} still includes the leaver.
     */
    public static String afterLeave(
            List<GameSession.PartyMember> beforeLeave,
            List<GameSession.PartyMember> afterLeave,
            String departedName) {
        if (afterLeave == null || afterLeave.isEmpty()) {
            return "";
        }
        if (beforeLeave == null || beforeLeave.isEmpty()) {
            return openingHolder(afterLeave);
        }
        int idx = indexOf(beforeLeave, departedName);
        if (idx < 0) {
            return openingHolder(afterLeave);
        }
        for (int step = 1; step <= beforeLeave.size(); step++) {
            GameSession.PartyMember candidate = beforeLeave.get((idx + step) % beforeLeave.size());
            if (candidate == null || candidate.name == null) {
                continue;
            }
            if (PartyRules.normalizeAlias(candidate.name)
                    .equals(PartyRules.normalizeAlias(departedName))) {
                continue;
            }
            if (indexOf(afterLeave, candidate.name) >= 0) {
                return candidate.name.trim();
            }
        }
        return openingHolder(afterLeave);
    }

    /** Restore export {@code turnName} when that alias is still in the party; else first member. */
    public static String restore(GameSession session) {
        if (session == null || session.partyMembers == null || session.partyMembers.isEmpty()) {
            return "";
        }
        if (session.turnName != null
                && !session.turnName.isBlank()
                && indexOf(session.partyMembers, session.turnName) >= 0) {
            for (GameSession.PartyMember member : session.partyMembers) {
                if (PartyRules.normalizeAlias(member.name)
                        .equals(PartyRules.normalizeAlias(session.turnName))) {
                    return member.name.trim();
                }
            }
        }
        return openingHolder(session.partyMembers);
    }

    private static int indexOf(List<GameSession.PartyMember> party, String name) {
        String key = PartyRules.normalizeAlias(name);
        if (key.isEmpty() || party == null) {
            return -1;
        }
        for (int i = 0; i < party.size(); i++) {
            GameSession.PartyMember member = party.get(i);
            if (member != null && key.equals(PartyRules.normalizeAlias(member.name))) {
                return i;
            }
        }
        return -1;
    }
}
