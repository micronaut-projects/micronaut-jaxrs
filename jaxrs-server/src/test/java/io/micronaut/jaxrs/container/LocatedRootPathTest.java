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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rest of the path a located target matches is not matched with the {@code @Path} of the
 * root resources: a root resource that matches it does not hide the routes of the target.
 */
@MicronautTest
@Property(name = "spec.name", value = "LocatedRootPathTest")
class LocatedRootPathTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void locatedRouteMatchingTheRootPathOfAnotherResource() {
        assertEquals("cart items", client.toBlocking().retrieve(HttpRequest.GET("/located-shop/cart/api/located-items")));
    }

    @Test
    void otherRootResource() {
        assertEquals("items", client.toBlocking().retrieve(HttpRequest.GET("/located-items")));
    }

    @Requires(property = "spec.name", value = "LocatedRootPathTest")
    @Path("located-items")
    public static class Items {
        @GET
        public String items() {
            return "items";
        }
    }

    @Requires(property = "spec.name", value = "LocatedRootPathTest")
    @Path("located-shop")
    public static class Shop {
        @Path("cart")
        public Object cart() {
            return new Cart();
        }
    }

    public static class Cart {
        @GET
        // the rest of the path, /api/located-items, is the path of the root resource Items
        @Path("api/located-items")
        public String items() {
            return "cart items";
        }
    }
}
