package io.questshift.api;

import io.questshift.session.SessionService;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

/**
 * Live gameplay socket. Text frames are player commands; the engine replies with JSON session
 * snapshots.
 */
@ServerEndpoint("/ws/sessions/{sessionId}")
public class GameSocket {

    private SessionService sessions() {
        return CDI.current().select(SessionService.class).get();
    }

    @OnOpen
    public void onOpen(Session socket, @PathParam("sessionId") String sessionId) {
        send(socket, sessions().export(sessionId, "json"));
    }

    @OnMessage
    public void onMessage(
            String command, Session socket, @PathParam("sessionId") String sessionId) {
        sessions().submit(sessionId, command, "shared");
        send(socket, sessions().export(sessionId, "json"));
    }

    private void send(Session socket, String payload) {
        socket.getAsyncRemote().sendText(payload);
    }
}
