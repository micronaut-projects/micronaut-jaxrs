package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JAX-RS behaviour of the server with the default server configuration of Micronaut, without
 * {@code micronaut.server.dispatch-options-requests} and {@code micronaut.server.not-found-on-missing-body}.
 */
@MicronautTest
@Property(name = "spec.name", value = "ServerDefaultsTest")
class ServerDefaultsTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void explicitOptionsMethod() {
        // JAX-RS 3.3.5: an OPTIONS request is dispatched to a resource method annotated with @OPTIONS
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.OPTIONS("/defaults/options"), String.class);
        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals("explicit", response.header("X-Options"));
        assertEquals("options", response.body());
    }

    @Test
    void automaticOptions() {
        // JAX-RS 3.3.5: without an @OPTIONS method, OPTIONS gets an automatic response with an Allow header
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.OPTIONS("/defaults/auto"), String.class);
        assertEquals(HttpStatus.OK, response.getStatus());
        String allow = response.header("Allow");
        Set<String> methods = Arrays.stream(allow.split(",")).map(String::trim).collect(Collectors.toSet());
        assertTrue(methods.containsAll(Set.of("GET", "PUT", "OPTIONS")), allow);
        assertFalse(methods.contains("POST"), allow);
    }

    @Test
    void nullEntityIsNoContent() {
        // JAX-RS 3.3.3: a null return value is mapped to 204
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/defaults/null"), String.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
    }

    @Test
    void voidIsNoContent() {
        // JAX-RS 3.3.3: a void resource method gets a 204
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/defaults/void"), String.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
    }

    @Test
    void missingEntityIsPassedAsNull() {
        // JAX-RS 4.2.4 / 3.3.2.1: a resource method with an entity parameter accepts a request without an entity
        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.create(HttpMethod.POST, "/defaults/entity").contentType(MediaType.TEXT_PLAIN_TYPE), String.class);
        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals("entity=", response.body());
    }

    @Requires(property = "spec.name", value = "ServerDefaultsTest")
    @Path("/defaults")
    public static class DefaultsResource {

        @OPTIONS
        @Path("options")
        @Produces("text/plain")
        public Response options() {
            return Response.ok("options").header("X-Options", "explicit").build();
        }

        @GET
        @Path("auto")
        @Produces("text/plain")
        public String get() {
            return "get";
        }

        @PUT
        @Path("auto")
        @Consumes("text/plain")
        public void put(String body) {
        }

        @GET
        @Path("null")
        @Produces("text/plain")
        public String nullEntity() {
            return null;
        }

        @GET
        @Path("void")
        public void nothing() {
        }

        @POST
        @Path("entity")
        @Consumes("text/plain")
        @Produces("text/plain")
        public String entity(String entity) {
            return "entity=" + (entity == null ? "" : entity);
        }
    }
}
