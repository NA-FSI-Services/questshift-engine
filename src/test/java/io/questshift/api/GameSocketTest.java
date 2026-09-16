package io.questshift.api;

import static io.restassured.RestAssured.given;
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
        socket.sendText("", true);
        assertTrue(replied.await(5, TimeUnit.SECONDS), last.get());
        assertTrue(last.get().contains(sessionId), last.get());
        assertTrue(last.get().contains("room-01-broken-shell"), last.get());
    }
}
