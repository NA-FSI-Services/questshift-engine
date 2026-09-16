package io.questshift.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionFanOutTest {

    @Test
    void fansTheSamePayloadToEveryOpenSocketThenDropsClosedOnes() {
        SessionFanOut hub = new SessionFanOut();
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        AtomicBoolean firstOpen = new AtomicBoolean(true);
        AtomicBoolean secondOpen = new AtomicBoolean(true);
        Session a = fakeSocket(first, firstOpen);
        Session b = fakeSocket(second, secondOpen);
        hub.attach(a, "s1");
        hub.attach(b, "s1");
        hub.fanOut("s1", "{\"mapX\":200}");
        assertEquals(List.of("{\"mapX\":200}"), first);
        assertEquals(List.of("{\"mapX\":200}"), second);

        secondOpen.set(false);
        hub.fanOut("s1", "closed-skip");
        assertEquals(List.of("{\"mapX\":200}"), second);
        assertEquals(List.of("{\"mapX\":200}", "closed-skip"), first);

        hub.detach(a);
        hub.detach(b);
        hub.fanOut("s1", "gone");
        assertEquals(2, first.size());
        hub.fanOut("missing", "noop");
        hub.send(null, "noop");
        hub.attach(null, "s1");
        hub.detach(null);
        assertTrue(firstOpen.get());
    }

    @Test
    void ignoresBlankAttachAndUnknownDetach() {
        SessionFanOut hub = new SessionFanOut();
        List<String> inbox = new ArrayList<>();
        Session socket = fakeSocket(inbox, new AtomicBoolean(true));
        hub.attach(socket, " ");
        hub.fanOut("s1", "none");
        assertTrue(inbox.isEmpty());
        hub.detach(socket);
        hub.fanOut(null, "none");
        hub.fanOut("s1", null);
    }

    private static Session fakeSocket(List<String> inbox, AtomicBoolean open) {
        Map<String, Object> props = new HashMap<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        RemoteEndpoint.Async async =
                (RemoteEndpoint.Async)
                        Proxy.newProxyInstance(
                                loader,
                                new Class<?>[] {RemoteEndpoint.Async.class},
                                (proxy, method, args) -> {
                                    if ("sendText".equals(method.getName())
                                            && args != null
                                            && args.length >= 1) {
                                        inbox.add(String.valueOf(args[0]));
                                    }
                                    return defaultValue(method.getReturnType());
                                });
        return (Session)
                Proxy.newProxyInstance(
                        loader,
                        new Class<?>[] {Session.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "isOpen" -> open.get();
                                    case "getUserProperties" -> props;
                                    case "getAsyncRemote" -> async;
                                    case "equals" ->
                                            args != null
                                                    && args.length == 1
                                                    && System.identityHashCode(proxy)
                                                            == System.identityHashCode(args[0]);
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "toString" -> "fake-socket";
                                    default -> defaultValue(method.getReturnType());
                                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class) {
            return null;
        }
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
