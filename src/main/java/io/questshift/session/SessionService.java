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

    private static final String SHARED_SEAT = "shared";

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
            spawnParty(session, first);
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
            Campaign campaign = campaigns.require(session.campaignId);
            PresenceRules.spawnAt(member, campaign.roomById(session.currentRoomId));
            members.add(member);
            session.partyMembers = members;
            return session;
        } finally {
            partyLock.unlock();
        }
    }

    public GameSession leave(String sessionId, String name) {
        partyLock.lock();
        try {
            GameSession session = get(sessionId);
            if (name == null || name.isBlank()) {
                throw new PartyInvalidException("Alias is required.");
            }
            String key = PartyRules.normalizeAlias(name);
            List<GameSession.PartyMember> members = new ArrayList<>();
            boolean found = false;
            for (GameSession.PartyMember existing : session.partyMembers) {
                if (key.equals(PartyRules.normalizeAlias(existing.name))) {
                    found = true;
                    continue;
                }
                members.add(existing);
            }
            if (found) {
                session.partyMembers = members;
            }
            return session;
        } finally {
            partyLock.unlock();
        }
    }

    public String delete(String sessionId) {
        partyLock.lock();
        try {
            GameSession session = get(sessionId);
            unindex(session);
            return session.id;
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

    public CommandResult submit(String sessionId, String command, String seatId, String name) {
        GameSession session = get(sessionId);
        Campaign campaign = campaigns.require(session.campaignId);
        Campaign.Room room = campaign.roomById(session.currentRoomId);
        CommandEvaluator.Evaluation evaluation = evaluator.evaluate(session, room, command);
        CommandResult result = new CommandResult();
        result.passed = evaluation.passed();
        result.message = evaluation.message();
        result.seatId = seatId;
        result.command = command;
        recordCommand(session, name, seatId, command, evaluation.passed(), evaluation.message());
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
        if (evaluation.authoredMiss()) {
            session.lastNarrative = evaluation.message();
            session.lastHint = evaluation.message();
            session.lastCanvasEvent = "focus_room";
            result.session = session;
            return result;
        }
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
            if (imported.commandLog == null) {
                imported.commandLog = new ArrayList<>();
            } else {
                imported.commandLog = new ArrayList<>(imported.commandLog);
            }
            if (imported.foundClues == null) {
                imported.foundClues = new ArrayList<>();
            } else {
                imported.foundClues = new ArrayList<>(imported.foundClues);
            }
            for (GameSession.PartyMember member : imported.partyMembers) {
                if (member.foundClues == null) {
                    member.foundClues = new ArrayList<>();
                } else {
                    member.foundClues = new ArrayList<>(member.foundClues);
                }
            }
            refreshStatus(imported);
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

    public GameSession updatePresence(String sessionId, PresenceRequest body) {
        partyLock.lock();
        try {
            GameSession session = get(sessionId);
            if (!"active".equals(session.status)) {
                throw new PartyConflictException(
                        "party_not_active", "This hour is no longer active.");
            }
            if (body == null || body.name == null || body.name.isBlank()) {
                throw new PartyInvalidException("Alias is required.");
            }
            GameSession.PartyMember member = findMember(session, body.name);
            if (member == null) {
                throw new PartyInvalidException("Unknown party member.");
            }
            Campaign campaign = campaigns.require(session.campaignId);
            String viewed = body.viewedRoomId == null ? "" : body.viewedRoomId.trim();
            if (!viewed.isEmpty()) {
                Campaign.Room room = campaign.roomById(viewed);
                if (room == null) {
                    throw new PartyInvalidException("Unknown room.");
                }
                if (!PresenceRules.roomUnlocked(
                        viewed, session.currentRoomId, session.puzzleCompletion)) {
                    throw new PartyInvalidException("That room is still sealed.");
                }
            }
            member.mapX = body.mapX;
            member.mapY = body.mapY;
            member.viewedRoomId = viewed;
            String pickup = body.pickupClueId == null ? "" : body.pickupClueId.trim();
            if (!pickup.isEmpty()) {
                Campaign.Clue clue = PresenceRules.clueInRoom(campaign, viewed, pickup);
                if (clue == null) {
                    throw new PartyInvalidException("Unknown clue.");
                }
                if (member.foundClues == null) {
                    member.foundClues = new ArrayList<>();
                }
                if (!member.foundClues.contains(clue.id)) {
                    List<String> mine = new ArrayList<>(member.foundClues);
                    mine.add(clue.id);
                    member.foundClues = mine;
                }
                if (session.foundClues == null) {
                    session.foundClues = new ArrayList<>();
                }
                if (!session.foundClues.contains(clue.id)) {
                    List<String> found = new ArrayList<>(session.foundClues);
                    found.add(clue.id);
                    session.foundClues = found;
                }
            }
            return session;
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

    private void unindex(GameSession session) {
        sessions.remove(session.id);
        if (session.joinCode != null) {
            byJoinCode.remove(JoinCodes.normalize(session.joinCode), session);
        }
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

    private void recordCommand(
            GameSession session,
            String name,
            String seatId,
            String command,
            boolean passed,
            String message) {
        if (command == null || command.isBlank()) {
            return;
        }
        if (session.commandLog == null) {
            session.commandLog = new ArrayList<>();
        }
        GameSession.CommandLogEntry entry = new GameSession.CommandLogEntry();
        entry.roomId = session.currentRoomId;
        entry.name = resolveAlias(session, name, seatId);
        entry.seatId = seatId == null || seatId.isBlank() ? SHARED_SEAT : seatId.trim();
        entry.command = command;
        entry.passed = passed;
        entry.message = message;
        List<GameSession.CommandLogEntry> log = new ArrayList<>(session.commandLog);
        log.add(entry);
        session.commandLog = log;
    }

    private static String resolveAlias(GameSession session, String name, String seatId) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        String seat = seatId == null ? "" : seatId.trim();
        for (GameSession.PartyMember member : session.partyMembers) {
            if (seat.equals(member.seatId)) {
                return member.name;
            }
        }
        return SHARED_SEAT;
    }

    private static GameSession.PartyMember findMember(GameSession session, String name) {
        String key = PartyRules.normalizeAlias(name);
        for (GameSession.PartyMember member : session.partyMembers) {
            if (key.equals(PartyRules.normalizeAlias(member.name))) {
                return member;
            }
        }
        return null;
    }

    private static void spawnParty(GameSession session, Campaign.Room room) {
        for (GameSession.PartyMember member : session.partyMembers) {
            PresenceRules.spawnAt(member, room);
        }
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

    public static class PresenceRequest {
        public String name;
        public int mapX;
        public int mapY;
        public String viewedRoomId;
        public String pickupClueId;
    }
}
