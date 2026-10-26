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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response of a resource method with an event sink starts on its first event or when it
 * returns, whichever comes first (JAX-RS 9.5): a method can wait for more events than the sink
 * buffers before the response.
 */
@MicronautTest
@Property(name = "spec.name", value = "SseFirstSendTest")
class SseFirstSendTest {

    static final int EVENTS = JaxRsSseEventSink.UNSUBSCRIBED_LIMIT + 10;

    @Inject
    EmbeddedServer server;

    @Test
    void methodWaitingForEachEventCompletes() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/sse-first-send"))
            .timeout(Duration.ofSeconds(20))
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("data: " + (EVENTS - 1)), response.body());
        }
    }

    @Requires(property = "spec.name", value = "SseFirstSendTest")
    @Path("/sse-first-send")
    public static class Resource {

        @GET
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void events(@Context SseEventSink sink, @Context Sse sse) throws java.io.IOException {
            try (sink) {
                for (int i = 0; i < EVENTS; i++) {
                    sink.send(sse.newEvent(String.valueOf(i))).toCompletableFuture().join();
                }
            }
        }
    }
}
