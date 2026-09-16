package io.questshift.session;

import io.questshift.campaign.Campaign;
import io.questshift.campaign.CampaignLibrary;
import io.questshift.engine.CommandEvaluator;
import io.questshift.llm.LLMService;
import io.questshift.llm.LLMService.GameMasterTurn;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class SessionService {

    private static final int DEFAULT_DURATION_MINUTES = 60;

    private static final int MIN_DURATION_MINUTES = 1;

    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();

    private final Map<String, GameSession> byJoinCode = new ConcurrentHashMap<>();

    private final Lock partyLock = new ReentrantLock();

    @Inject CampaignLibrary campaigns;

    @Inject CommandEvaluator evaluator;

    @Inject LLMService llm;

    @Inject StateSerializer serializer;

    public GameSession start(String campaignId, List<GameSession.PartyMember> party) {
        partyLock.lock();
        try {
            GameSession live = findLiveParty();
            if (live != null) {
                throw new PartyActiveException(live.joinCode);
            }
            Campaign campaign =
                    campaignId == null || campaignId.isBlank()
                            ? campaigns.defaultCampaign()
                            : campaigns.require(campaignId);
            Campaign.Room first = campaign.firstRoom();
            GameSession session = new GameSession();
            session.id = UUID.randomUUID().toString();
            session.joinCode = JoinCodes.allocate(occupiedJoinCodes());
            session.campaignId = campaign.metadata.id;
            session.startedAt = Instant.now();
            session.currentRoomId = first.id;
            session.partyMembers = PartyRules.requireOpeningParty(party);
            campaign.rooms.forEach(room -> session.puzzleCompletion.put(room.id, false));
            GameMasterTurn turn = llm.narrate(campaign, session, first, campaign.story.opening);
            applyTurn(session, turn);
            index(session);
            return session;
        } finally {
            partyLock.unlock();
        }
    }

    public GameSession addMember(String sessionId, GameSession.PartyMember raw) {
        partyLock.lock();
        try {
            GameSession session = get(sessionId);
            if (!"active".equals(session.status)) {
                throw new PartyConflictException(
                        "party_not_active", "This hour is no longer active.");
            }
            GameSession.PartyMember member = PartyRules.requireMember(raw);
            String key = PartyRules.normalizeAlias(member.name);
            for (GameSession.PartyMember existing : session.partyMembers) {
                if (key.equals(PartyRules.normalizeAlias(existing.name))) {
                    return session;
                }
            }
            if (session.partyMembers.size() >= PartyRules.MAX_MEMBERS) {
                throw new PartyConflictException("party_full", "The party is full (8).");
            }
            List<GameSession.PartyMember> members = new ArrayList<>(session.partyMembers);
            members.add(member);
            session.partyMembers = members;
            return session;
        } finally {
            partyLock.unlock();
        }
    }

    public GameSession get(String id) {
        GameSession session = sessions.get(id);
        if (session == null) {
            session = byJoinCode.get(JoinCodes.normalize(id));
        }
        if (session == null) {
            throw new NotFoundException("No session " + id);
        }
        refreshStatus(session);
        return session;
    }

    public CommandResult submit(String sessionId, String command, String seatId) {
        GameSession session = get(sessionId);
        Campaign campaign = campaigns.require(session.campaignId);
        Campaign.Room room = campaign.roomById(session.currentRoomId);
        CommandEvaluator.Evaluation evaluation = evaluator.evaluate(session, room, command);
        CommandResult result = new CommandResult();
        result.passed = evaluation.passed();
        result.message = evaluation.message();
        result.seatId = seatId;
        result.command = command;
        if (evaluation.passed()) {
            session.puzzleCompletion.put(room.id, true);
            if (room.loot != null) {
                room.loot.forEach(
                        loot -> {
                            if (!session.inventory.contains(loot.id)) {
                                session.inventory.add(loot.id);
                            }
                        });
            }
            if (room.skillsGranted != null) {
                room.skillsGranted.forEach(
                        skill -> {
                            if (!session.skills.contains(skill)) {
                                session.skills.add(skill);
                            }
                        });
            }
            session.lastCanvasEvent = room.canvasEvent;
            Campaign.Room next = campaign.nextRoom(room.id);
            GameMasterTurn turn;
            if (next == null) {
                session.status = "complete";
                session.currentRoomId = room.id;
                turn = llm.narrate(campaign, session, room, room.successNarrative);
            } else {
                session.currentRoomId = next.id;
                turn = llm.narrate(campaign, session, next, room.successNarrative);
            }
            applyTurn(session, turn);
            result.session = session;
            return result;
        }
        session.hintCount++;
        GameMasterTurn miss =
                llm.narrate(
                        campaign,
                        session,
                        room,
                        "The party tried: "
                                + command
                                + ". It failed. Offer a short miss beat, then the hint if they are stuck.");
        applyTurn(session, miss);
        result.session = session;
        return result;
    }

    public GameSession restore(GameSession imported) {
        partyLock.lock();
        try {
            if (imported.id == null || imported.id.isBlank()) {
                imported.id = UUID.randomUUID().toString();
            }
            if (imported.partyMembers == null) {
                imported.partyMembers = new ArrayList<>();
            } else {
                imported.partyMembers = new ArrayList<>(imported.partyMembers);
            }
            refreshStatus(imported);
            GameSession live = findLiveParty();
            if (isLive(imported) && live != null && !live.id.equals(imported.id)) {
                throw new PartyActiveException(live.joinCode);
            }
            Set<String> taken = occupiedJoinCodesExcluding(imported.id);
            String wanted = JoinCodes.normalize(imported.joinCode);
            if (!JoinCodes.isJoinCode(wanted) || taken.contains(wanted)) {
                imported.joinCode = JoinCodes.allocate(taken);
            } else {
                imported.joinCode = wanted;
            }
            index(imported);
            return imported;
        } finally {
            partyLock.unlock();
        }
    }

    /** Test helper: drop in-memory parties so each @QuarkusTest can Start. */
    public void clear() {
        partyLock.lock();
        try {
            sessions.clear();
            byJoinCode.clear();
        } finally {
            partyLock.unlock();
        }
    }

    public String export(String sessionId, String format) {
        GameSession session = get(sessionId);
        if (format != null && format.toLowerCase(Locale.ROOT).contains("json")) {
            return serializer.toJson(session);
        }
        return serializer.toYaml(session);
    }

    public GameSession restoreRaw(String body, String format) {
        return restore(serializer.from(body, format));
    }

    private void index(GameSession session) {
        GameSession previous = sessions.put(session.id, session);
        if (previous != null && previous.joinCode != null) {
            byJoinCode.remove(JoinCodes.normalize(previous.joinCode), previous);
        }
        byJoinCode.put(JoinCodes.normalize(session.joinCode), session);
    }

    private GameSession findLiveParty() {
        for (GameSession session : sessions.values()) {
            refreshStatus(session);
            if (isLive(session)) {
                return session;
            }
        }
        return null;
    }

    private boolean isLive(GameSession session) {
        return "active".equals(session.status);
    }

    private void refreshStatus(GameSession session) {
        session.tickElapsed();
        if (!"active".equals(session.status) || session.campaignId == null) {
            return;
        }
        int minutes = DEFAULT_DURATION_MINUTES;
        try {
            minutes = campaigns.require(session.campaignId).metadata.durationMinutes;
        } catch (IllegalArgumentException ignored) {
            // Keep the 60-minute freeze if the campaign map is empty.
        }
        if (minutes < MIN_DURATION_MINUTES) {
            minutes = DEFAULT_DURATION_MINUTES;
        }
        if (session.elapsedSeconds >= minutes * 60L) {
            session.status = "expired";
        }
    }

    private Set<String> occupiedJoinCodes() {
        return occupiedJoinCodesExcluding(null);
    }

    private Set<String> occupiedJoinCodesExcluding(String sessionId) {
        Set<String> taken = new HashSet<>();
        for (GameSession session : sessions.values()) {
            if (sessionId != null && sessionId.equals(session.id)) {
                continue;
            }
            String code = JoinCodes.normalize(session.joinCode);
            if (!code.isEmpty()) {
                taken.add(code);
            }
        }
        return taken;
    }

    private void applyTurn(GameSession session, GameMasterTurn turn) {
        session.lastNarrative = turn.narrative;
        session.lastHint = turn.hint;
        session.yamlFallback = turn.yamlFallback;
        if (turn.canvasEvent != null) {
            session.lastCanvasEvent = turn.canvasEvent;
        }
    }

    public static class CommandResult {
        public boolean passed;
        public String message;
        public String command;
        public String seatId;
        public GameSession session;
    }
}
