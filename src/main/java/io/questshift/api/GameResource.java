package io.questshift.api;

import io.questshift.campaign.Campaign;
import io.questshift.campaign.CampaignLibrary;
import io.questshift.session.GameSession;
import io.questshift.session.PartyActiveException;
import io.questshift.session.PartyConflictException;
import io.questshift.session.PartyInvalidException;
import io.questshift.session.SessionService;
import io.questshift.session.SessionService.CommandResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameResource {

    @Inject SessionService sessions;

    @Inject CampaignLibrary campaigns;

    @Inject SessionFanOut fanOut;

    @GET
    @Path("/campaigns")
    public Collection<Campaign> listCampaigns() {
        return campaigns.all();
    }

    @POST
    @Path("/sessions")
    public GameSession start(StartRequest request) {
        StartRequest body = request == null ? new StartRequest() : request;
        try {
            return sessions.start(body.campaignId, body.party);
        } catch (PartyActiveException e) {
            throw conflict(e, e.getJoinCode(), "party_active", e.getMessage());
        } catch (PartyConflictException e) {
            throw conflict(e, null, e.getErrorCode(), e.getMessage());
        } catch (PartyInvalidException e) {
            throw invalidParty(e);
        }
    }

    @GET
    @Path("/sessions/{id}")
    public GameSession get(@PathParam("id") String id) {
        return sessions.get(id);
    }

    @POST
    @Path("/sessions/{id}/party")
    public GameSession addParty(@PathParam("id") String id, GameSession.PartyMember member) {
        try {
            return sessions.addMember(id, member);
        } catch (PartyConflictException e) {
            throw conflict(e, null, e.getErrorCode(), e.getMessage());
        } catch (PartyInvalidException e) {
            throw invalidParty(e);
        }
    }

    @POST
    @Path("/sessions/{id}/presence")
    public GameSession presence(
            @PathParam("id") String id, SessionService.PresenceRequest request) {
        try {
            GameSession session = sessions.updatePresence(id, request);
            fanOut.fanOut(session.id, sessions.export(session.id, "json"));
            return session;
        } catch (PartyConflictException e) {
            throw conflict(e, null, e.getErrorCode(), e.getMessage());
        } catch (PartyInvalidException e) {
            throw invalidPresence(e);
        }
    }

    @POST
    @Path("/sessions/{id}/commands")
    public CommandResult command(@PathParam("id") String id, CommandRequest request) {
        CommandRequest body = request == null ? new CommandRequest() : request;
        return sessions.submit(id, body.command, body.seatId, body.name);
    }

    @GET
    @Path("/sessions/{id}/export")
    @Produces({"application/yaml", MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    public Response export(@PathParam("id") String id, @QueryParam("format") String format) {
        String chosen = format == null ? "yaml" : format;
        String body = sessions.export(id, chosen);
        String media =
                chosen.toLowerCase(Locale.ROOT).contains("json")
                        ? MediaType.APPLICATION_JSON
                        : "application/yaml";
        return Response.ok(body).type(media).build();
    }

    @POST
    @Path("/sessions/import")
    @Consumes({
        MediaType.APPLICATION_JSON,
        "application/yaml",
        MediaType.TEXT_PLAIN,
        MediaType.WILDCARD
    })
    public GameSession restore(String body, @QueryParam("format") String format) {
        try {
            return sessions.restoreRaw(body, format);
        } catch (PartyActiveException e) {
            throw conflict(e, e.getJoinCode(), "party_active", e.getMessage());
        }
    }

    private static WebApplicationException conflict(
            Throwable cause, String joinCode, String error, String message) {
        ApiError body = new ApiError();
        body.error = error;
        body.message = message;
        body.joinCode = joinCode;
        return new WebApplicationException(
                cause, Response.status(409).type(MediaType.APPLICATION_JSON).entity(body).build());
    }

    private static WebApplicationException invalidParty(RuntimeException e) {
        ApiError body = new ApiError();
        body.error = "invalid_party";
        body.message = e.getMessage();
        return new WebApplicationException(
                e, Response.status(400).type(MediaType.APPLICATION_JSON).entity(body).build());
    }

    private static WebApplicationException invalidPresence(RuntimeException e) {
        ApiError body = new ApiError();
        body.error = "invalid_presence";
        body.message = e.getMessage();
        return new WebApplicationException(
                e, Response.status(400).type(MediaType.APPLICATION_JSON).entity(body).build());
    }

    public static class StartRequest {
        public String campaignId;
        public List<GameSession.PartyMember> party;
    }

    public static class CommandRequest {
        public String command;
        public String seatId;
        public String name;
    }

    public static class ApiError {
        public String error;
        public String message;
        public String joinCode;
    }
}
