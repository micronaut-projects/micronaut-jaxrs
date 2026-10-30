package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resumed response is done once its processing ends, not while a response filter processes it
 * (JAX-RS, AsyncResponse#isDone).
 */
@MicronautTest
@Property(name = "spec.name", value = "AsyncResponseDoneTest")
class AsyncResponseDoneTest {

    static final CompletableFuture<AsyncResponse> RESPONSE = new CompletableFuture<>();
    static final CountDownLatch FILTERING = new CountDownLatch(1);
    static final CountDownLatch RELEASE = new CountDownLatch(1);

    @Inject
    EmbeddedServer server;

    @Test
    void notDoneWhileAResponseFilterRuns() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            CompletableFuture<HttpResponse<String>> pending = client.sendAsync(
                HttpRequest.newBuilder(URI.create(server.getURL() + "/api/async-done")).build(), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(FILTERING.await(5, TimeUnit.SECONDS));
                AsyncResponse response = RESPONSE.get(5, TimeUnit.SECONDS);
                assertFalse(response.isSuspended());
                assertFalse(response.isDone());
            } finally {
                RELEASE.countDown();
            }
            assertEquals("done", pending.get(5, TimeUnit.SECONDS).body());
            AsyncResponse response = RESPONSE.get();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!response.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(response.isDone());
        }
    }

    @Requires(property = "spec.name", value = "AsyncResponseDoneTest")
    @Provider
    public static class BlockingFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
            FILTERING.countDown();
            try {
                RELEASE.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }
    }

    @Requires(property = "spec.name", value = "AsyncResponseDoneTest")
    @Path("/async-done")
    public static class Resource {
        @GET
        @Produces("text/plain")
        public void get(@Suspended AsyncResponse response) {
            RESPONSE.complete(response);
            response.resume("done");
        }
    }
}
