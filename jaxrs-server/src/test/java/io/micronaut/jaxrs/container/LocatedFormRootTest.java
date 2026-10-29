package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A locator located at runtime under a resource created with a form field reads the form before
 * it locates its target, not the field from the query.
 */
@MicronautTest
@Property(name = "spec.name", value = "LocatedFormRootTest")
class LocatedFormRootTest {

    @Inject
    @Client("/api/located-form")
    HttpClient client;

    @Test
    void resourceMethodReadsTheFormField() {
        assertEquals("form a", client.toBlocking().retrieve(HttpRequest.POST("/", Map.of("name", "a"))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)));
    }

    @Test
    void locatorLocatedAtRuntimeReadsTheForm() {
        assertEquals("located a", client.toBlocking().retrieve(HttpRequest.POST("/located?name=query", Map.of("name", "a"))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)));
    }

    @Requires(property = "spec.name", value = "LocatedFormRootTest")
    @Path("/located-form")
    public static class Root {
        @FormParam("name")
        String name;

        @POST
        public String post() {
            return "form " + name;
        }

        @Path("located")
        public Object located() {
            return new Target(name);
        }
    }

    public static class Target {
        private final String name;

        public Target(String name) {
            this.name = name;
        }

        @POST
        public String post() {
            return "located " + name;
        }
    }
}
