package io.micronaut.jaxrs.conformance;

import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.sse.InboundSseEvent;
import jakarta.ws.rs.sse.SseEventSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Random event streams served raw by a JDK HTTP server and read by the JAX-RS client {@link SseEventSource}; the
 * oracle is the event stream interpretation of the HTML standard (9.2.6), which JAX-RS 9.1 refers to.
 */
@Timeout(150)
class SseConformanceTest {
    private static HttpServer server;
    private static Client client;
    private static final Map<String, byte[]> STREAMS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = STREAMS.get(path);
            if (body == null || HITS.computeIfAbsent(path, p -> new AtomicInteger()).getAndIncrement() > 0) {
                // the stream is served once: the reconnection is told there is nothing more
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = ClientBuilder.newClient();
    }

    @AfterAll
    static void stop() {
        client.close();
        server.stop(0);
    }

    record Ev(String name, String id, String data, long retry) {
    }

    /** The HTML standard 9.2.6 interpretation, keeping the fields that the event block itself declares. */
    static List<Ev> oracle(String stream) {
        List<Ev> events = new ArrayList<>();
        if (stream.startsWith("\uFEFF")) {
            stream = stream.substring(1);
        }
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        boolean complete = false;
        while (i < stream.length()) {
            char c = stream.charAt(i);
            if (c == '\r' || c == '\n') {
                lines.add(stream.substring(start, i));
                if (c == '\r' && i + 1 < stream.length() && stream.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
            i++;
        }
        // an incomplete last line is not processed
        StringBuilder data = new StringBuilder();
        String name = null;
        String id = null;
        long retry = -1;
        for (String line : lines) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    String d = data.substring(0, data.length() - 1);
                    events.add(new Ev(name, id, d, retry));
                }
                data.setLength(0);
                name = null;
                id = null;
                retry = -1;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> name = value.isEmpty() ? null : value;
                case "data" -> data.append(value).append('\n');
                case "id" -> {
                    if (value.indexOf('\0') < 0) {
                        id = value;
                    }
                }
                case "retry" -> {
                    if (!value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
                        retry = Long.parseLong(value);
                    }
                }
                default -> {
                }
            }
        }
        return events;
    }

    static String escape(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n").replace("\uFEFF", "<BOM>");
    }

    @Test
    void seededEventStreams() throws Exception {
        long seed = 0x53534521L;
        Random r = new Random(seed);
        String[] lines = {"data:x", "data: x", "data:  x", "data", "data:", "data:a:b", ":comment", ": c", "event:e", "event: f", "event",
            "id:1", "id: 2", "id", "retry:5", "retry: 7", "retry:x", "retry:1.5", "foo:bar", "DATA:x", "data :x", " data:x", "data:\u00e9"};
        String[] eols = {"\n", "\r\n", "\r"};
        Map<String, String> failures = new LinkedHashMap<>();
        int cases = 250;
        for (int c = 0; c < cases; c++) {
            StringBuilder sb = new StringBuilder();
            if (r.nextInt(10) == 0) {
                sb.append('\uFEFF');
            }
            boolean sameEol = r.nextBoolean();
            String eol = eols[r.nextInt(eols.length)];
            int n = 1 + r.nextInt(12);
            for (int k = 0; k < n; k++) {
                if (r.nextInt(3) == 0) {
                    sb.append(sameEol ? eol : eols[r.nextInt(eols.length)]);
                } else {
                    sb.append(lines[r.nextInt(lines.length)]).append(sameEol ? eol : eols[r.nextInt(eols.length)]);
                }
            }
            if (r.nextInt(4) != 0) {
                sb.append(sameEol ? eol : "\n");
            }
            String stream = sb.toString();
            List<Ev> expected = oracle(stream);
            String path = "/s" + c;
            STREAMS.put(path, stream.getBytes(StandardCharsets.UTF_8));
            List<InboundSseEvent> received = new CopyOnWriteArrayList<>();
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            try (SseEventSource source = SseEventSource.target(client.target("http://localhost:" + server.getAddress().getPort() + path))
                .reconnectingEvery(5, TimeUnit.MILLISECONDS).build()) {
                source.register(received::add, e -> {
                    errors.add(e);
                    done.countDown();
                }, done::countDown);
                source.open();
                if (!done.await(10, TimeUnit.SECONDS)) {
                    failures.putIfAbsent("timeout", escape(stream));
                    continue;
                }
            }
            List<Ev> actual = new ArrayList<>();
            for (InboundSseEvent e : received) {
                if (e.isEmpty() && e.getComment() != null && !e.getComment().isEmpty() && expected.stream().noneMatch(x -> x.data.isEmpty())) {
                    // a comment delivered as an event of its own: allowed by JAX-RS, skipped here
                    continue;
                }
                actual.add(new Ev(e.getName(), e.getId(), e.readData(), e.getReconnectDelay()));
            }
            String diff = diff(expected, actual);
            if (!errors.isEmpty()) {
                failures.putIfAbsent("error " + errors.get(0).getClass().getSimpleName(), escape(stream) + "\n    " + errors.get(0));
            } else if (diff != null) {
                failures.putIfAbsent(diff, escape(stream) + "\n    expected " + expected + "\n    actual   " + actual);
            }
        }
        StringBuilder report = new StringBuilder("seed=" + seed + " cases=" + cases + " failure kinds=" + failures.size() + "\n");
        failures.forEach((k, v) -> report.append("== ").append(k).append("\n    ").append(v).append('\n'));
        System.out.println(report);
        assertTrue(failures.isEmpty(), report::toString);
    }

    private static String diff(List<Ev> expected, List<Ev> actual) {
        if (expected.size() != actual.size()) {
            return "count";
        }
        for (int i = 0; i < expected.size(); i++) {
            Ev e = expected.get(i);
            Ev a = actual.get(i);
            if (!e.data.equals(a.data)) {
                return "data";
            }
            if (e.name != null && !e.name.equals(a.name) || e.name == null && a.name != null && !a.name.equals("message") && !a.name.isEmpty()) {
                return "name";
            }
            if (e.id != null && !e.id.equals(a.id)) {
                return "id";
            }
            if (e.retry != a.retry) {
                return "retry";
            }
        }
        return null;
    }
}
