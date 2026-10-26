package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A resource method returning a subclass of {@code CompletionStage} of the application is
 * asynchronous: the response is its value (JAX-RS 8.2.2).
 */
@MicronautTest
@Property(name = "spec.name", value = "CompletionStageSubclassTest")
class CompletionStageSubclassTest {

    @Inject
    @Client("/api/stage-subclass")
    HttpClient client;

    @Test
    void valueOfASubclassIsTheResponse() {
        assertEquals("done", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    public static class MyFuture extends CompletableFuture<String> {
    }

    @Requires(property = "spec.name", value = "CompletionStageSubclassTest")
    @Path("/stage-subclass")
    public static class Resource {

        @GET
        public MyFuture get() {
            MyFuture future = new MyFuture();
            future.complete("done");
            return future;
        }
    }
}
