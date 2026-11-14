package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An asynchronous resource method that reads an entity runs on the executor of the
 * {@code @ExecuteOn} of its class.
 */
@MicronautTest
@Property(name = "spec.name", value = "AsyncEntityClassExecutorTest")
@Property(name = "micronaut.executors.my-pool.type", value = "fixed")
@Property(name = "micronaut.executors.my-pool.number-of-threads", value = "2")
class AsyncEntityClassExecutorTest {

    @Inject
    EmbeddedServer server;

    @Test
    void classExecutorOfAnAsynchronousMethodWithAnEntity() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(server.getURL() + "/api/class-executor"))
                    .header("Content-Type", MediaType.TEXT_PLAIN)
                    .POST(HttpRequest.BodyPublishers.ofString("entity")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().startsWith("entity:my-pool"), response.body());
        }
    }

    @Requires(property = "spec.name", value = "AsyncEntityClassExecutorTest")
    @Path("/class-executor")
    @ExecuteOn("my-pool")
    public static class ClassExecutorResource {
        @POST
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        public CompletionStage<String> post(String entity) {
            return CompletableFuture.completedFuture(entity + ":" + Thread.currentThread().getName());
        }
    }
}
