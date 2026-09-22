package io.questshift.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.questshift.session.SessionService;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GameSocketTest {

    @Inject SessionService sessions;

    @BeforeEach
    void clearParties() {
        sessions.clear();
    }

    @Test
    void openPushesJsonSnapshot() throws Exception {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(
                                "{\"campaignId\":\"devops-dungeon\",\"party\":[{\"name\":\"Ada\",\"seatId\":\"guardian\"}]}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> payload = new AtomicReference<>();
        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                        URI.create(
                                "ws://127.0.0.1:" + RestAssured.port + "/ws/sessions/" + sessionId),
                        new WebSocket.Listener() {
                            @Override
                            public void onOpen(WebSocket webSocket) {
                                webSocket.request(1);
                            }

                            @Override
                            public CompletionStage<?> onText(
                                    WebSocket webSocket, CharSequence data, boolean last) {
                                payload.set(data.toString());
                                latch.countDown();
                                return CompletableFuture.completedFuture(null);
                            }
                        })
                .join();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "websocket snapshot");
        assertTrue(payload.get().contains(sessionId), payload.get());
    }

    @Test
    void commandFramePushesUpdatedSnapshot() throws Exception {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(
                                "{\"campaignId\":\"devops-dungeon\",\"party\":[{\"name\":\"Ada\",\"seatId\":\"guardian\"}]}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");

        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch replied = new CountDownLatch(1);
        AtomicReference<String> last = new AtomicReference<>();
        WebSocket socket =
                HttpClient.newHttpClient()
                        .newWebSocketBuilder()
                        .buildAsync(
                                URI.create(
                                        "ws://127.0.0.1:"
                                                + RestAssured.port
                                                + "/ws/sessions/"
                                                + sessionId),
                                new WebSocket.Listener() {
                                    @Override
                                    public void onOpen(WebSocket webSocket) {
                                        webSocket.request(1);
                                    }

                                    @Override
                                    public CompletionStage<?> onText(
                                            WebSocket webSocket,
                                            CharSequence data,
                                            boolean lastFrame) {
                                        last.set(data.toString());
                                        if (opened.getCount() > 0) {
                                            opened.countDown();
                                        } else {
                                            replied.countDown();
                                        }
                                        webSocket.request(1);
                                        return CompletableFuture.completedFuture(null);
                                    }
                                })
                        .join();

        assertTrue(opened.await(5, TimeUnit.SECONDS), "websocket open snapshot");
        socket.sendText("cat /var/log/quest.log", true);
        assertTrue(replied.await(5, TimeUnit.SECONDS), last.get());
        assertTrue(last.get().contains(sessionId), last.get());
        assertTrue(last.get().contains("room-01-broken-shell"), last.get());
        assertTrue(last.get().contains("commandLog"), last.get());
        assertTrue(last.get().contains("cat /var/log/quest.log"), last.get());
    }

    @Test
    void presencePostFansSnapshotToEveryOpenSocket() throws Exception {
        var started =
                given().contentType(ContentType.JSON)
                        .body(
                                "{\"campaignId\":\"devops-dungeon\",\"party\":[{\"name\":\"Ada\",\"seatId\":\"guardian\"}]}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract();
        String sessionId = started.path("id");
        String joinCode = started.path("joinCode");
        String scoringRoom = started.path("currentRoomId");

        SocketInbox first = openInbox(sessionId);
        SocketInbox second = openInbox(sessionId);
        assertTrue(first.opened.await(5, TimeUnit.SECONDS), "first websocket open");
        assertTrue(second.opened.await(5, TimeUnit.SECONDS), "second websocket open");

        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Ada\",\"mapX\":200,\"mapY\":276,\"viewedRoomId\":\"\"}")
                .when()
                .post("/api/sessions/" + joinCode + "/presence")
                .then()
                .statusCode(200)
                .body("partyMembers[0].mapX", equalTo(200))
                .body("currentRoomId", equalTo(scoringRoom));

        assertTrue(first.updated.await(5, TimeUnit.SECONDS), first.last.get());
        assertTrue(second.updated.await(5, TimeUnit.SECONDS), second.last.get());
        assertTrue(compactJson(first.last.get()).contains("\"mapX\":200"), first.last.get());
        assertTrue(compactJson(second.last.get()).contains("\"mapX\":200"), second.last.get());
        assertTrue(first.last.get().contains(scoringRoom), first.last.get());
        assertTrue(second.last.get().contains(scoringRoom), second.last.get());
        assertTrue(first.last.get().contains(sessionId), first.last.get());

        first.socket.sendText("cat /var/log/quest.log", true);
        assertTrue(first.command.await(5, TimeUnit.SECONDS), first.last.get());
        assertTrue(first.last.get().contains("cat /var/log/quest.log"), first.last.get());
        assertFalse(
                second.command.await(400, TimeUnit.MILLISECONDS),
                "command frames stay on the sender; presence fan-out must not change that");
        assertFalse(second.last.get().contains("cat /var/log/quest.log"), second.last.get());
        first.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        second.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void commandPostFansSnapshotToEveryOpenSocket() throws Exception {
        String sessionId =
                given().contentType(ContentType.JSON)
                        .body(
                                "{\"campaignId\":\"devops-dungeon\",\"party\":[{\"name\":\"Ada\",\"seatId\":\"guardian\"}]}")
                        .when()
                        .post("/api/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("id");

        SocketInbox first = openInbox(sessionId);
        SocketInbox second = openInbox(sessionId);
        assertTrue(first.opened.await(5, TimeUnit.SECONDS), "first websocket open");
        assertTrue(second.opened.await(5, TimeUnit.SECONDS), "second websocket open");

        given().contentType(ContentType.JSON)
                .body(
                        "{\"command\":\"cat /var/log/quest.log\",\"seatId\":\"guardian\",\"name\":\"Ada\"}")
                .when()
                .post("/api/sessions/" + sessionId + "/commands")
                .then()
                .statusCode(200)
                .body("session.commandLog[0].command", equalTo("cat /var/log/quest.log"))
                .body("session.commandLog[0].name", equalTo("Ada"));

        assertTrue(first.updated.await(5, TimeUnit.SECONDS), first.last.get());
        assertTrue(second.updated.await(5, TimeUnit.SECONDS), second.last.get());
        assertTrue(first.last.get().contains("cat /var/log/quest.log"), first.last.get());
        assertTrue(second.last.get().contains("cat /var/log/quest.log"), second.last.get());
        assertTrue(first.last.get().contains("\"commandLog\""), first.last.get());
        assertTrue(second.last.get().contains("Ada"), second.last.get());
        first.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        second.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    private static SocketInbox openInbox(String sessionId) {
        SocketInbox inbox = new SocketInbox();
        inbox.socket =
                HttpClient.newHttpClient()
                        .newWebSocketBuilder()
                        .buildAsync(
                                URI.create(
                                        "ws://127.0.0.1:"
                                                + RestAssured.port
                                                + "/ws/sessions/"
                                                + sessionId),
                                inbox)
                        .join();
        return inbox;
    }

    private static String compactJson(String payload) {
        return payload == null ? "" : payload.replaceAll("\\s+", "");
    }

    private static final class SocketInbox implements WebSocket.Listener {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch updated = new CountDownLatch(1);
        final CountDownLatch command = new CountDownLatch(1);
        final AtomicReference<String> last = new AtomicReference<>();
        WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket, CharSequence data, boolean lastFrame) {
            last.set(data.toString());
            if (opened.getCount() > 0) {
                opened.countDown();
            } else if (updated.getCount() > 0) {
                updated.countDown();
            } else {
                command.countDown();
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
