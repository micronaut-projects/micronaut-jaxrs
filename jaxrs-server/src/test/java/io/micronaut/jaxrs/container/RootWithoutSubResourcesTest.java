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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A root resource class without sub-resource methods or locators matches its own path only
 * (JAX-RS 3.7.2, step 1(c)).
 */
@MicronautTest
@Property(name = "spec.name", value = "RootWithoutSubResourcesTest")
class RootWithoutSubResourcesTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void rootWithoutSubResourcesDoesNotMatchALongerPath() {
        // JAX-RS 3.7.2, step 1(c)
        assertEquals("a", client.toBlocking().retrieve(HttpRequest.GET("/lm4/a")));
        assertEquals("a/b", client.toBlocking().retrieve(HttpRequest.GET("/lm4/a/b")));
    }

    @Requires(property = "spec.name", value = "RootWithoutSubResourcesTest")
    @Path("/lm4/a")
    public static class LeafRoot {
        @GET
        @Produces("text/plain")
        public String get() {
            return "a";
        }
    }

    @Requires(property = "spec.name", value = "RootWithoutSubResourcesTest")
    @Path("/lm4")
    public static class TemplateRoot {
        @GET
        @Path("{a}/{b}")
        @Produces("text/plain")
        public String get(@PathParam("a") String a, @PathParam("b") String b) {
            return a + "/" + b;
        }
    }
}
