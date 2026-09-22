package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Matrix parameters of the last path segment are bound to method and constructor parameters, and
 * are not part of the path the route matches.
 */
@MicronautTest
@Property(name = "spec.name", value = "MatrixParamTest")
class MatrixParamTest {

    @Inject
    @Client("/api/matrix-param")
    HttpClient client;

    @Test
    void matrixParamsAreBound() {
        assertEquals("red [a, b c] 2 owner=fred",
            client.toBlocking().retrieve(HttpRequest.GET("/cars;color=red;tags=a;tags=b%20c;size=2;owner=fred")));
    }

    @Test
    void missingMatrixParamsHaveTheirDefaults() {
        assertEquals("none [] 0 owner=nobody", client.toBlocking().retrieve(HttpRequest.GET("/cars")));
    }

    @Test
    void matrixParamsOnAMiddleSegmentDoNotChangeTheRoute() {
        assertEquals("details of 7", client.toBlocking().retrieve(HttpRequest.GET("/cars;color=red/7;trim=gt/details")));
    }

    @Requires(property = "spec.name", value = "MatrixParamTest")
    @Path("/matrix-param/cars")
    public static class CarsResource {
        private final String owner;

        public CarsResource(@MatrixParam("owner") @DefaultValue("nobody") String owner) {
            this.owner = owner;
        }

        @GET
        @Produces("text/plain")
        public String cars(@MatrixParam("color") @DefaultValue("none") String color,
                           @MatrixParam("tags") List<String> tags,
                           @MatrixParam("size") int size) {
            return color + " " + tags + " " + size + " owner=" + owner;
        }

        @GET
        @Path("{id}/details")
        @Produces("text/plain")
        public String details(@PathParam("id") int id) {
            return "details of " + id;
        }
    }
}
