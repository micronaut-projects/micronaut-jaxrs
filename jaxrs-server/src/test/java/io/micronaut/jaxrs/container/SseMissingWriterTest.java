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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sending an event whose data no message body writer writes fails the send (JAX-RS 9.3).
 */
@MicronautTest
@Property(name = "spec.name", value = "SseMissingWriterTest")
class SseMissingWriterTest {

    @Inject
    EmbeddedServer server;

    @Test
    void sendWithoutAWriterFails() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/sse-missing-writer"))
            .timeout(Duration.ofSeconds(20))
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("data: failed=true"), response.body());
            assertFalse(response.body().contains("Unwritable@"), response.body());
        }
    }

    public static final class Unwritable {
    }

    @Requires(property = "spec.name", value = "SseMissingWriterTest")
    @Path("/sse-missing-writer")
    public static class Resource {

        @GET
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void events(@Context SseEventSink sink, @Context Sse sse) throws java.io.IOException {
            try (sink) {
                boolean failed = sink.send(sse.newEventBuilder()
                        .data(new Unwritable())
                        .mediaType(MediaType.valueOf("application/x-unknown"))
                        .build())
                    .toCompletableFuture()
                    .handle((ignored, failure) -> failure != null)
                    .join();
                sink.send(sse.newEvent("failed=" + failed)).toCompletableFuture().join();
            }
        }
    }
}
