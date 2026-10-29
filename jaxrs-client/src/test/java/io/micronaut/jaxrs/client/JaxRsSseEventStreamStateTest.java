package io.micronaut.jaxrs.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.sse.SseEventSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event source follows the retry and the id of a block of the stream without data, as the
 * client reads them (HTML, server-sent events, 9.2.6).
 */
class JaxRsSseEventStreamStateTest {

    // far longer than the retry of the stream
    private static final long CONFIGURED_DELAY = 20_000;

    private HttpServer server;
    private final AtomicInteger connections = new AtomicInteger();
    private final BlockingQueue<Connection> connected = new LinkedBlockingQueue<>();
    private volatile String firstStream = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/events", this::events);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void events(HttpExchange exchange) throws IOException {
        int connection = connections.incrementAndGet();
        connected.add(new Connection(System.nanoTime(), exchange.getRequestHeaders().getFirst("Last-Event-ID")));
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream body = exchange.getResponseBody();
        if (connection == 1) {
            body.write(firstStream.getBytes(StandardCharsets.UTF_8));
            body.flush();
            // the stream breaks: the connection closes without the end of the chunked body
            exchange.close();
        } else {
            body.write("data: again\n\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
            exchange.close();
        }
    }

    private SseEventSource source(Client client) {
        return SseEventSource.target(client.target("http://localhost:" + server.getAddress().getPort()).path("events"))
            .reconnectingEvery(CONFIGURED_DELAY, TimeUnit.MILLISECONDS)
            .build();
    }

    @Test
    void theRetryOfTheStreamIsTheReconnectionDelay() throws Exception {
        firstStream = "retry: 50\n\ndata: hi\n\n";
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = source(client)) {
            List<String> events = new CopyOnWriteArrayList<>();
            source.register(event -> events.add(event.readData()));
            source.open();
            Connection first = connected.poll(10, TimeUnit.SECONDS);
            Connection second = connected.poll(10, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second, "not reconnected after the retry of the stream, events " + events);
            long delay = TimeUnit.NANOSECONDS.toMillis(second.time() - first.time());
            assertTrue(delay >= 50 && delay < CONFIGURED_DELAY / 2, "reconnected after " + delay + " ms");
            assertEquals("hi", events.get(0));
        } finally {
            client.close();
        }
    }

    @Test
    void theIdOfABlockWithoutDataIsTheLastEventId() throws Exception {
        firstStream = "retry: 50\n\ndata: hi\nid: 3\n\nid: 7\n\n";
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = source(client)) {
            List<String> ids = new CopyOnWriteArrayList<>();
            source.register(event -> ids.add(event.getId() + ":" + event.readData()));
            source.open();
            Connection first = connected.poll(10, TimeUnit.SECONDS);
            Connection second = connected.poll(10, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(second, "not reconnected, events " + ids);
            assertEquals(null, first.lastEventId());
            assertEquals("7", second.lastEventId());
            // the block without data is no event
            assertEquals("3:hi", ids.get(0));
        } finally {
            client.close();
        }
    }

    @Test
    void theEventsAreUnchanged() throws Exception {
        firstStream = "retry: 50\n\nevent: greeting\nid: 1\ndata: one\n\ndata: two\ndata: lines\n\n";
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = source(client)) {
            List<String> events = new CopyOnWriteArrayList<>();
            source.register(event -> events.add(event.getName() + "|" + event.getId() + "|" + event.readData()));
            source.open();
            assertNotNull(connected.poll(10, TimeUnit.SECONDS));
            Connection second = connected.poll(10, TimeUnit.SECONDS);
            assertNotNull(second);
            // the id of an event is the last event id
            assertEquals("1", second.lastEventId());
            assertEquals("greeting|1|one", events.get(0));
            assertTrue(events.get(1).endsWith("|two\nlines"), events::toString);
        } finally {
            client.close();
        }
    }

    private record Connection(long time, String lastEventId) {
    }
}
