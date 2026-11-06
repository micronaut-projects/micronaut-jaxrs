package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.runtime.http.scope.RequestScope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.ws.rs.container.ResourceContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
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

    @Test
    void postConstructSeesTheValuesOfTheRequest() {
        assertEquals("value|value", client.toBlocking().retrieve(HttpRequest.GET("/post-construct?x=value")));
    }

    @Test
    void perRequestResourcesAreDestroyedOnceTheResponseIsDone() throws InterruptedException {
        int plain = DestroyedResource.DESTROYED.get();
        int reading = ReadingDestroyedResource.DESTROYED.get();
        assertEquals("destroyed", client.toBlocking().retrieve(HttpRequest.GET("/destroyed")));
        assertEquals("value", client.toBlocking().retrieve(HttpRequest.GET("/reading-destroyed?x=value")));
        long deadline = System.currentTimeMillis() + 5000;
        while ((DestroyedResource.DESTROYED.get() == plain || ReadingDestroyedResource.DESTROYED.get() == reading)
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(plain + 1, DestroyedResource.DESTROYED.get());
        assertEquals(reading + 1, ReadingDestroyedResource.DESTROYED.get());
    }

    @Test
    void requestScopedResourceWithRequestValuesIsTheInstanceOfItsScope() {
        assertEquals("true value", client.toBlocking().retrieve(HttpRequest.GET("/request-scoped?x=value")));
    }

    @Test
    void requestScopedResourceMembersAreSetBeforePostConstruct() {
        assertEquals("value|value", client.toBlocking().retrieve(HttpRequest.GET("/request-scoped-post-construct?x=value")));
        assertEquals("other|other", client.toBlocking().retrieve(HttpRequest.GET("/request-scoped-post-construct?x=other")));
    }

    @Requires(property = "spec.name", value = "ResourceLifecycleTest")
    @Path("/lifecycle/destroyed")
    public static class DestroyedResource {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @GET
        public String get() {
            return "destroyed";
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @Requires(property = "spec.name", value = "ResourceLifecycleTest")
    @Path("/lifecycle/reading-destroyed")
    public static class ReadingDestroyedResource {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @QueryParam("x")
        String x;

        @GET
        public String get() {
            return x;
        }

        @PreDestroy
        void destroy() {
            DESTROYED.incrementAndGet();
        }
    }

    @Requires(property = "spec.name", value = "ResourceLifecycleTest")
    @RequestScope
    @Path("/lifecycle/request-scoped")
    public static class RequestScopedResource {
        @QueryParam("x")
        String x;

        @Context
        ResourceContext resourceContext;

        @GET
        public String get() {
            RequestScopedResource same = resourceContext.getResource(RequestScopedResource.class);
            return (same.id() == id()) + " " + same.x();
        }

        // through the scoped proxy, which delegates to the instance of the request
        public Object id() {
            return this;
        }

        public String x() {
            return x;
        }
    }

    @Requires(property = "spec.name", value = "ResourceLifecycleTest")
    @RequestScope
    @Path("/lifecycle/request-scoped-post-construct")
    public static class RequestScopedPostConstructResource {
        @QueryParam("x")
        String x;

        private String copied;

        @PostConstruct
        void init() {
            copied = x;
        }

        @GET
        public String get() {
            return x + "|" + copied;
        }
    }

    @Requires(property = "spec.name", value = "ResourceLifecycleTest")
    @Path("/lifecycle/post-construct")
    public static class PostConstructResource {
        @QueryParam("x")
        String x;

        private String copied;

        @PostConstruct
        void init() {
            copied = x;
        }

        @GET
        public String get() {
            return x + "|" + copied;
        }
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
