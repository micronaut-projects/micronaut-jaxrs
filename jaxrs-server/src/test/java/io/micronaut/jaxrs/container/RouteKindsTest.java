package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Resource methods with a custom HTTP method, and asynchronous ones that read an entity.
 */
@MicronautTest
@Property(name = "spec.name", value = "RouteKindsTest")
class RouteKindsTest {

    @Inject
    @Client("/api/route-kinds")
    HttpClient client;

    @Test
    void customHttpMethod() {
        assertEquals("propfind 7",
            client.toBlocking().retrieve(HttpRequest.create(HttpMethod.CUSTOM, "/7", "PROPFIND")));
    }

    @Test
    void customHttpMethodKeepsItsCase() {
        assertEquals("foo",
            client.toBlocking().retrieve(HttpRequest.create(HttpMethod.CUSTOM, "/verbs", "foo")));
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.create(HttpMethod.CUSTOM, "/verbs", "FOO")));
        assertEquals(405, e.getStatus().getCode());
    }

    @Test
    void asynchronousMethodWithAnEntity() {
        assertEquals("async hello",
            client.toBlocking().retrieve(HttpRequest.POST("/async", "hello").contentType(MediaType.TEXT_PLAIN_TYPE)));
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @jakarta.ws.rs.HttpMethod("PROPFIND")
    @interface PROPFIND {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @jakarta.ws.rs.HttpMethod("foo")
    @interface Foo {
    }

    @Requires(property = "spec.name", value = "RouteKindsTest")
    @Path("/route-kinds")
    public static class RouteKindsResource {

        @PROPFIND
        @Path("/{id}")
        @Produces("text/plain")
        public String propfind(@PathParam("id") int id) {
            return "propfind " + id;
        }

        @Foo
        @Path("/verbs")
        @Produces("text/plain")
        public String foo() {
            return "foo";
        }

        @POST
        @Path("/async")
        @Consumes("text/plain")
        @Produces("text/plain")
        public CompletionStage<String> async(String entity) {
            return CompletableFuture.supplyAsync(() -> "async " + entity);
        }
    }
}
