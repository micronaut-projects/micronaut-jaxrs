package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sub-resource locators whose target is known only at runtime.
 */
@MicronautTest
@Property(name = "spec.name", value = "LocatedSubResourceTest")
class LocatedSubResourceTest {

    @Inject
    @Client("/api/located")
    HttpClient client;

    @Test
    void locatorReturningObject() {
        assertEquals("book 5 page 12", client.toBlocking().retrieve(HttpRequest.GET("/books/5/pages/12")));
    }

    @Test
    void locatorReturningObjectWithoutMatchingRoute() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/books/5/chapters")));
        assertEquals(404, e.getStatus().getCode());
    }

    @Test
    void locatorThrowingBeforeReturning() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/throwing/409")));
        assertEquals(409, e.getStatus().getCode());
    }

    @Test
    void recursiveLocatorDeeperThanTheStaticDepth() {
        assertEquals("depth 6", client.toBlocking().retrieve(HttpRequest.GET("/node/node/node/node/node/node/depth")));
    }

    @Requires(property = "spec.name", value = "LocatedSubResourceTest")
    @Path("/located")
    public static class Root {

        @Path("books/{book}")
        public Object book(@PathParam("book") int book) {
            return new Book(book);
        }

        @Path("throwing/{status}")
        public Response throwing(@PathParam("status") int status) {
            throw new WebApplicationException(status);
        }

        @Path("node")
        public Node node() {
            return new Node(1);
        }
    }

    public static class Book {
        private final int id;

        public Book(int id) {
            this.id = id;
        }

        @GET
        @Path("pages/{page}")
        public String page(@PathParam("page") int page) {
            return "book " + id + " page " + page;
        }
    }

    public static class Node {
        private final int depth;

        public Node(int depth) {
            this.depth = depth;
        }

        @Path("node")
        public Node node() {
            return new Node(depth + 1);
        }

        @GET
        @Path("depth")
        public String depth() {
            return "depth " + depth;
        }
    }
}
