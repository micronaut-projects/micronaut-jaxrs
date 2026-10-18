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
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resource method sends server-sent events to its sink (JAX-RS 9.3).
 */
@MicronautTest
@Property(name = "spec.name", value = "SseTest")
class SseTest {

    @Inject
    EmbeddedServer server;

    @Test
    void eventsAreSent() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/sse/events")).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith(MediaType.SERVER_SENT_EVENTS), response.headers().toString());
            String body = response.body();
            assertTrue(body.contains("event: greeting\n"), body);
            assertTrue(body.contains("id: 1\n"), body);
            assertTrue(body.contains("data: hello\n"), body);
            assertTrue(body.contains("data: 42\n"), body);
        }
    }

    @Test
    void sendFailsAfterTheClientDisconnects() throws Exception {
        SseResource.DISCONNECTED.set(new CompletableFuture<>());
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/sse/endless")).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                // an event arrived: the stages complete as the events are written
                assertTrue(body.read() >= 0);
            }
        }
        // a later send fails: its event cannot be written
        Throwable failure = SseResource.DISCONNECTED.get().get(30, TimeUnit.SECONDS);
        assertNotNull(failure);
    }

    @Requires(property = "spec.name", value = "SseTest")
    @Path("/sse")
    public static class SseResource {
        @GET
        @Path("events")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void events(@Context SseEventSink sink, @Context Sse sse) throws java.io.IOException {
            try (sink) {
                sink.send(sse.newEventBuilder().name("greeting").id("1").data("hello").build());
                sink.send(sse.newEventBuilder().data(Integer.class, 42).build());
            }
        }

        static final AtomicReference<CompletableFuture<Throwable>> DISCONNECTED = new AtomicReference<>(new CompletableFuture<>());

        @GET
        @Path("endless")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void endless(@Context SseEventSink sink, @Context Sse sse) {
            CompletableFuture<Throwable> disconnected = DISCONNECTED.get();
            Thread.ofVirtual().start(() -> {
                String data = "x".repeat(1024);
                // a producer chaining its sends: it stops when a send fails
                for (int i = 0; i < 1_000_000; i++) {
                    try {
                        sink.send(sse.newEvent(data)).toCompletableFuture().join();
                    } catch (RuntimeException e) {
                        disconnected.complete(e);
                        return;
                    }
                }
                disconnected.complete(null);
            });
        }
    }
}
