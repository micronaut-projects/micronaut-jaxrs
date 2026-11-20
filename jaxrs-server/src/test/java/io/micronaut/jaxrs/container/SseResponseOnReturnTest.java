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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response of a resource method with an event sink is sent when the method returns, also
 * when it sent no event yet, e.g. one that registers the sink to send the events later: the
 * client receives the status and the headers before the first event.
 */
@MicronautTest
@Property(name = "spec.name", value = "SseResponseOnReturnTest")
class SseResponseOnReturnTest {

    static final CompletableFuture<SseEventSink> SINK = new CompletableFuture<>();
    static volatile Sse sse;

    @Inject
    EmbeddedServer server;

    @Test
    void theResponseIsSentWhenTheMethodReturns() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/sse-on-return")).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            // completes with the status and the headers, before any event
            HttpResponse<InputStream> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).get(10, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            SseEventSink sink = SINK.get(10, TimeUnit.SECONDS);
            sink.send(sse.newEvent("later")).toCompletableFuture().get(10, TimeUnit.SECONDS);
            sink.close();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String body = String.join("\n", reader.lines().toList());
                assertTrue(body.contains("data: later"), body);
            }
        }
    }

    @Requires(property = "spec.name", value = "SseResponseOnReturnTest")
    @Path("/sse-on-return")
    public static class Resource {

        @GET
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void register(@Context SseEventSink sink, @Context Sse sse) {
            SseResponseOnReturnTest.sse = sse;
            SINK.complete(sink);
        }
    }
}
