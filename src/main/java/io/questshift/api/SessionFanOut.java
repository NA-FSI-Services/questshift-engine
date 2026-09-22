package io.questshift.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.websocket.Session;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Open {@code /ws/sessions/{sessionId}} sockets, keyed by {@link
 * io.questshift.session.GameSession#id}. REST presence, join, leave, and scored commands fan the
 * same JSON snapshot to every subscriber so every browser in the party sees walks, the shared
 * board, and GM prose. WebSocket text frames still reply to the sender only (they cannot hold the
 * floor).
 */
@ApplicationScoped
public class SessionFanOut {

    static final String SESSION_KEY = "questshift.sessionId";

    private final Map<String, Set<Session>> sockets = new ConcurrentHashMap<>();

    public void attach(Session socket, String sessionId) {
        if (socket == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        socket.getUserProperties().put(SESSION_KEY, sessionId);
        sockets.computeIfAbsent(sessionId, id -> new CopyOnWriteArraySet<>()).add(socket);
    }

    public void detach(Session socket) {
        if (socket == null) {
            return;
        }
        Object key = socket.getUserProperties().get(SESSION_KEY);
        if (!(key instanceof String sessionId) || sessionId.isBlank()) {
            return;
        }
        Set<Session> live = sockets.get(sessionId);
        if (live == null) {
            return;
        }
        live.remove(socket);
        if (live.isEmpty()) {
            sockets.remove(sessionId, live);
        }
    }

    public void send(Session socket, String payload) {
        if (socket == null || payload == null || !socket.isOpen()) {
            return;
        }
        socket.getAsyncRemote().sendText(payload);
    }

    public void fanOut(String sessionId, String payload) {
        if (sessionId == null || payload == null) {
            return;
        }
        Set<Session> live = sockets.get(sessionId);
        if (live == null) {
            return;
        }
        for (Session socket : live) {
            send(socket, payload);
        }
    }

    public void drop(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sockets.remove(sessionId);
    }
}
