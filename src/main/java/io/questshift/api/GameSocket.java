package io.questshift.api;

import io.questshift.session.SessionService;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

/**
 * Live gameplay socket. Text frames are player commands (seat {@code shared}, not the floor); the
 * engine replies to the sender with a JSON snapshot. REST presence, join, leave, and scored
 * commands fan the same snapshot to every open socket for the party.
 */
@ServerEndpoint("/ws/sessions/{sessionId}")
public class GameSocket {

    private SessionService sessions() {
        return CDI.current().select(SessionService.class).get();
    }

    private SessionFanOut fanOut() {
        return CDI.current().select(SessionFanOut.class).get();
    }

    @OnOpen
    public void onOpen(Session socket, @PathParam("sessionId") String sessionId) {
        String canonical = sessions().get(sessionId).id;
        fanOut().attach(socket, canonical);
        fanOut().send(socket, sessions().export(canonical, "json"));
    }

    @OnClose
    public void onClose(Session socket) {
        fanOut().detach(socket);
    }

    @OnError
    public void onError(Session socket, Throwable error) {
        if (error != null) {
            fanOut().detach(socket);
        }
    }

    @OnMessage
    public void onMessage(
            String command, Session socket, @PathParam("sessionId") String sessionId) {
        sessions().submit(sessionId, command, "shared", "");
        fanOut().send(socket, sessions().export(sessionId, "json"));
    }
}
