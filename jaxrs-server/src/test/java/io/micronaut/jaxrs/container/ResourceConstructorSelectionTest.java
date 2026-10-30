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
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A resource is created with the constructor with the most parameters of those JAX-RS can call
 * (JAX-RS 3.1.2): a longer one with parameters JAX-RS cannot supply is not selected.
 */
@MicronautTest
@Property(name = "spec.name", value = "ResourceConstructorSelectionTest")
class ResourceConstructorSelectionTest {

    @Inject
    @Client("/api/constructor-selection")
    HttpClient client;

    @Test
    void longestSuitableConstructor() {
        assertEquals("hello", client.toBlocking().retrieve(HttpRequest.GET("?x=hello")));
    }

    @Requires(property = "spec.name", value = "ResourceConstructorSelectionTest")
    @Path("/constructor-selection")
    public static class ConstructorResource {
        private final String value;

        public ConstructorResource() {
            value = "default";
        }

        public ConstructorResource(@QueryParam("x") String x) {
            value = x;
        }

        public ConstructorResource(String a, String b) {
            value = "unsuitable";
        }

        @GET
        public String get() {
            return value;
        }
    }
}
