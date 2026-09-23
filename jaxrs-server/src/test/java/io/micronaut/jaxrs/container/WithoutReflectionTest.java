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
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Without the micronaut-reflection module, what needs reflection fails with an error that names it.
 */
@MicronautTest
@Property(name = "spec.name", value = "WithoutReflectionTest")
@EnabledIfSystemProperty(named = "micronaut.jaxrs.test.reflection", matches = "false")
class WithoutReflectionTest {

    @Inject
    @Client("/api/without-reflection")
    HttpClient client;

    @Test
    void resourceMethodNeedsTheReflectionModule() {
        assertEquals("io.micronaut:micronaut-reflection",
            client.toBlocking().retrieve(HttpRequest.GET("/method"), String.class));
    }

    @Test
    void resourceClassIsKnownWithoutReflection() {
        assertEquals(Resource.class.getName(),
            client.toBlocking().retrieve(HttpRequest.GET("/class"), String.class));
    }

    @Requires(property = "spec.name", value = "WithoutReflectionTest")
    @Path("/without-reflection")
    public static class Resource {
        @Context
        ResourceInfo resourceInfo;

        @GET
        @Path("method")
        public String method() {
            try {
                return resourceInfo.getResourceMethod().getName();
            } catch (UnsupportedOperationException e) {
                return e.getMessage().contains("io.micronaut:micronaut-reflection") ? "io.micronaut:micronaut-reflection" : e.getMessage();
            }
        }

        @GET
        @Path("class")
        public String resourceClass() {
            return resourceInfo.getResourceClass().getName();
        }
    }
}
