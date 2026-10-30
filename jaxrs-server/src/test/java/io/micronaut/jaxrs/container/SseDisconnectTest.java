package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client disconnecting from an event stream closes its sink (JAX-RS 9.3): also before the first
 * event, and the close callbacks of a broadcaster do not run on the event loop.
 */
@MicronautTest
@Property(name = "spec.name", value = "SseDisconnectTest")
@Property(name = "micronaut.netty.event-loops.default.num-threads", value = "1")
class SseDisconnectTest {

    static final CountDownLatch ENTERED = new CountDownLatch(1);
    static final CountDownLatch RELEASE = new CountDownLatch(1);
    static final CompletableFuture<SseEventSink> EARLY = new CompletableFuture<>();
    static final CompletableFuture<String> CLOSE_THREAD = new CompletableFuture<>();
    static final CountDownLatch CALLBACK_RELEASE = new CountDownLatch(1);

    @Inject
    EmbeddedServer server;

    @Test
    void aDisconnectBeforeTheFirstEventClosesTheSink() throws Exception {
        try {
            try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
                socket.getOutputStream().write("GET /api/sse-disconnect/early HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertTrue(ENTERED.await(5, TimeUnit.SECONDS));
            }
            SseEventSink sink = EARLY.get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!sink.isClosed() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(sink.isClosed());
        } finally {
            RELEASE.countDown();
        }
    }

    @Test
    void aBlockingCloseCallbackOfABroadcasterDoesNotBlockTheEventLoop() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<InputStream> events = client.send(HttpRequest.newBuilder(URI.create(server.getURL() + "/api/sse-disconnect/connected")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = events.body()) {
                assertTrue(body.read() >= 0);
            }
            CLOSE_THREAD.get(5, TimeUnit.SECONDS);
            HttpResponse<String> ping = client.send(HttpRequest.newBuilder(URI.create(server.getURL() + "/api/sse-disconnect/ping"))
                .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("pong", ping.body());
        } finally {
            CALLBACK_RELEASE.countDown();
        }
    }

    @Requires(property = "spec.name", value = "SseDisconnectTest")
    @Path("/sse-disconnect")
    public static class Resource {

        static final SseBroadcaster BROADCASTER = new JaxRsSse().newBroadcaster();

        static {
            BROADCASTER.onClose(sink -> {
                CLOSE_THREAD.complete(Thread.currentThread().getName());
                try {
                    CALLBACK_RELEASE.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        @GET
        @Path("ping")
        public String ping() {
            return "pong";
        }

        @GET
        @Path("early")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void early(@Context SseEventSink sink) throws Exception {
            EARLY.complete(sink);
            ENTERED.countDown();
            RELEASE.await(10, TimeUnit.SECONDS);
            sink.close();
        }

        @GET
        @Path("connected")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void connected(@Context SseEventSink sink, @Context Sse sse) {
            BROADCASTER.register(sink);
            sink.send(sse.newEvent("hello"));
        }
    }
}
