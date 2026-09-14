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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SessionService {

    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();

    @Inject CampaignLibrary campaigns;

    @Inject CommandEvaluator evaluator;

    @Inject LLMService llm;

    @Inject StateSerializer serializer;

    public GameSession start(String campaignId, List<GameSession.PartyMember> party) {
        Campaign campaign =
                campaignId == null || campaignId.isBlank()
                        ? campaigns.defaultCampaign()
                        : campaigns.require(campaignId);
        Campaign.Room first = campaign.firstRoom();
        GameSession session = new GameSession();
        session.id = UUID.randomUUID().toString();
        session.campaignId = campaign.metadata.id;
        session.startedAt = Instant.now();
        session.currentRoomId = first.id;
        session.partyMembers =
                party == null || party.isEmpty()
                        ? List.of(
                                new GameSession.PartyMember("Facilitator", "guardian"),
                                new GameSession.PartyMember("Player 2", "automancer"),
                                new GameSession.PartyMember("Player 3", "ranger"),
                                new GameSession.PartyMember("Player 4", "artificer"))
                        : party;
        campaign.rooms.forEach(room -> session.puzzleCompletion.put(room.id, false));
        GameMasterTurn turn = llm.narrate(campaign, session, first, campaign.story.opening);
        applyTurn(session, turn);
        sessions.put(session.id, session);
        return session;
    }

    public GameSession get(String id) {
        GameSession session = sessions.get(id);
        if (session == null) {
            throw new NotFoundException("No session " + id);
        }
        session.tickElapsed();
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
        if (imported.id == null || imported.id.isBlank()) {
            imported.id = UUID.randomUUID().toString();
        }
        imported.tickElapsed();
        sessions.put(imported.id, imported);
        return imported;
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

    private void applyTurn(GameSession session, GameMasterTurn turn) {
        session.lastNarrative = turn.narrative;
        session.lastHint = turn.hint;
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
