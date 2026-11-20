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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A method with {@code @Path("/")} is a sub-resource method: at the path of its class, the
 * resource methods of the class are selected when it has some (JAX-RS 3.7.2, step 2a), so the
 * request method of the sub-resource method is not allowed there; it is when the class has none.
 */
@MicronautTest
@Property(name = "spec.name", value = "SlashSubResourceMethodTest")
class SlashSubResourceMethodTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void theResourceMethodIsSelected() {
        assertEquals("get", client.toBlocking().retrieve(HttpRequest.GET("/slash-mixed")));
    }

    @Test
    void theSubResourceMethodIsNotSelectedNextToResourceMethods() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.POST("/slash-mixed", "")));
        assertEquals(405, e.getStatus().getCode());
    }

    @Test
    void theSubResourceMethodIsSelectedWithoutResourceMethods() {
        assertEquals("post", client.toBlocking().retrieve(HttpRequest.POST("/slash-only", "")));
    }

    @Requires(property = "spec.name", value = "SlashSubResourceMethodTest")
    @Path("/slash-mixed")
    public static class Mixed {
        @GET
        @Produces("text/plain")
        public String get() {
            return "get";
        }

        @POST
        @Path("/")
        @Produces("text/plain")
        public String post() {
            return "post";
        }
    }

    @Requires(property = "spec.name", value = "SlashSubResourceMethodTest")
    @Path("/slash-only")
    public static class Only {
        @POST
        @Path("/")
        @Produces("text/plain")
        public String post() {
            return "post";
        }
    }
}
