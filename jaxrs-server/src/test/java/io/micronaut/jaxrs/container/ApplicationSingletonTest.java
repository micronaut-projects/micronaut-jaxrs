package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The instances of the application are its resources, when it is not started by the Java SE
 * bootstrap too: concurrent requests see their own request.
 */
@MicronautTest
@Property(name = "spec.name", value = "ApplicationSingletonTest")
class ApplicationSingletonTest {

    @Inject
    @Client("/api/application-singleton")
    HttpClient client;

    @Test
    void instanceOfTheApplicationIsTheResource() {
        assertEquals("the instance", client.toBlocking().retrieve(HttpRequest.GET("/value")));
    }

    @Test
    void concurrentRequestsSeeTheirOwnRequest() {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<String> responses;
        try {
            List<CompletableFuture<String>> futures = IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> client.toBlocking().retrieve(HttpRequest.GET("/slow/" + i)), executor))
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

    @Requires(property = "spec.name", value = "ApplicationSingletonTest")
    @Replaces(RestApplication.class)
    @Singleton
    @ApplicationPath("/api")
    public static class SingletonApplication extends Application {
        private final Resource resource = new Resource("the instance");

        @Override
        public Set<Object> getSingletons() {
            return Set.of(resource);
        }
    }

    @Path("/application-singleton")
    public static class Resource {
        private final String value;

        @Context
        UriInfo uriInfo;

        Resource(String value) {
            this.value = value;
        }

        @GET
        @Path("value")
        public String value() {
            return value;
        }

        @GET
        @Path("slow/{id}")
        public String slow(@PathParam("id") String id) throws InterruptedException {
            Thread.sleep(100);
            return id + " " + uriInfo.getPathParameters().getFirst("id");
        }
    }
}
