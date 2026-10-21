package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The lifecycle of the resources (JAX-RS 3.1.1): a new instance per request by default, and a
 * singleton whose {@code @Context} fields are proxies of the current request.
 */
@MicronautTest
@Property(name = "spec.name", value = "ResourceLifecycleTest")
class ResourceLifecycleTest {

    @Inject
    @Client("/api/lifecycle")
    HttpClient client;

    @Test
    void unscopedResourceIsCreatedPerRequest() {
        assertEquals("1", client.toBlocking().retrieve(HttpRequest.GET("/per-request")));
        assertEquals("1", client.toBlocking().retrieve(HttpRequest.GET("/per-request")));
    }

    @Test
    void singletonResourceKeepsItsStateAndSeesTheCurrentRequest() {
        int first = Integer.parseInt(client.toBlocking().retrieve(HttpRequest.GET("/singleton/count")).split(" ")[0]);
        assertEquals((first + 1) + " lifecycle/singleton/count", client.toBlocking().retrieve(HttpRequest.GET("/singleton/count")));
    }

    @Test
    void concurrentRequestsOfASingletonSeeTheirOwnRequest() {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<String> responses;
        try {
            List<CompletableFuture<String>> futures = IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> client.toBlocking().retrieve(HttpRequest.GET("/singleton/slow/" + i)), executor))
                .toList();
            responses = futures.stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdown();
        }
        assertEquals(8, responses.size());
        for (String response : responses) {
            String[] parts = response.split(" ");
            assertEquals(parts[0], parts[1]);
        }
        assertEquals(8, Set.copyOf(responses).size());
    }

    @Requires(property = "spec.name", value = "ResourceLifecycleTest")
    @Path("/lifecycle/per-request")
    public static class PerRequest {
        private int calls;

        @GET
        public String get() {
            return String.valueOf(++calls);
        }
    }

    @Requires(property = "spec.name", value = "ResourceLifecycleTest")
    @Singleton
    @Path("/lifecycle/singleton")
    public static class SingletonResource {
        private final AtomicInteger calls = new AtomicInteger();

        @Context
        UriInfo uriInfo;

        @GET
        @Path("count")
        public String count() {
            return calls.incrementAndGet() + " " + uriInfo.getPath();
        }

        @GET
        @Path("slow/{id}")
        public String slow(@PathParam("id") String id) throws InterruptedException {
            Thread.sleep(100);
            return id + " " + uriInfo.getPathParameters().getFirst("id");
        }
    }
}
