package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The JAX-RS container filters apply to the JAX-RS resource methods only, not to the routes of a
 * Micronaut controller of the same application.
 */
@MicronautTest
@Property(name = "spec.name", value = "ControllerRouteFilterTest")
class ControllerRouteFilterTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void theFiltersRunForAResource() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/api/controller-route-filter"), String.class);
        assertEquals("resource", response.body());
        assertEquals("yes", response.header("X-Filtered"));
    }

    @Test
    void theFiltersDoNotRunForAController() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/api/native-route-filter"), String.class);
        assertEquals("controller", response.body());
        assertNull(response.header("X-Filtered"));
    }

    @Requires(property = "spec.name", value = "ControllerRouteFilterTest")
    @Controller("/native-route-filter")
    public static class NativeController {
        @io.micronaut.http.annotation.Get(produces = "text/plain")
        public String get() {
            return "controller";
        }
    }

    @Requires(property = "spec.name", value = "ControllerRouteFilterTest")
    @Path("/controller-route-filter")
    public static class FilteredResource {
        @GET
        public String get() {
            return "resource";
        }
    }

    @Requires(property = "spec.name", value = "ControllerRouteFilterTest")
    @Provider
    public static class MarkingFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle("X-Filtered", "yes");
        }
    }
}
