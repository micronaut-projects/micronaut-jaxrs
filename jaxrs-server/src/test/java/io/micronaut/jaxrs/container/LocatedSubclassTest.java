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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A locator that returns a subclass of its declared return type: the routes of the subclass.
 */
@MicronautTest
@Property(name = "spec.name", value = "LocatedSubclassTest")
class LocatedSubclassTest {

    @Inject
    @Client("/api/located-subclass")
    HttpClient client;

    @Test
    void routeOfTheDeclaredClass() {
        assertEquals("base", client.toBlocking().retrieve(HttpRequest.GET("/extended/base")));
        assertEquals("base", client.toBlocking().retrieve(HttpRequest.GET("/plain/base")));
    }

    @Test
    void routeOfTheSubclass() {
        assertEquals("extra", client.toBlocking().retrieve(HttpRequest.GET("/extended/extra")));
    }

    @Requires(property = "spec.name", value = "LocatedSubclassTest")
    @Path("/located-subclass")
    public static class Root {
        @Path("{kind}")
        public Base locate(@PathParam("kind") String kind) {
            return "extended".equals(kind) ? new Extended() : new Base();
        }
    }

    public static class Base {
        @GET
        @Path("base")
        public String base() {
            return "base";
        }
    }

    public static class Extended extends Base {
        @GET
        @Path("extra")
        public String extra() {
            return "extra";
        }
    }
}
