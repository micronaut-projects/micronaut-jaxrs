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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }
}
