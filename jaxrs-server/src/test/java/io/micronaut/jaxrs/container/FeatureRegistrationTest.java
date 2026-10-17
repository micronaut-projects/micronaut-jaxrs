package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The registrations of a {@link Feature}: for the given contracts only, with the given
 * priorities, of a nested feature, of properties, and duplicates rejected (Configurable).
 */
@MicronautTest
@Property(name = "spec.name", value = "FeatureRegistrationTest")
class FeatureRegistrationTest {

    @Inject
    @Client("/api/feature-registration")
    HttpClient client;

    @Test
    void registrations() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/"), String.class);
        // B registered with the priority 50 runs before A (@Priority(100)), the duplicate of B is
        // rejected, the nested feature is configured, the request-only filter runs as one
        assertEquals("B,A,nested,request-only value [ContainerRequestFilter]", response.body());
        // not as a response filter: it was registered for ContainerRequestFilter only
        assertNull(response.header("X-Response-Only"));
    }

    private static void append(ContainerRequestContext context, String value) {
        String order = context.getHeaderString("X-Order");
        context.getHeaders().putSingle("X-Order", order == null ? value : order + "," + value);
    }

    // introspected: its @Priority is known without reflection
    @Introspected
    @Priority(100)
    public static class FilterA implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            append(requestContext, "A");
        }
    }

    @Introspected
    @Priority(200)
    public static class FilterB implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            append(requestContext, "B");
        }
    }

    public static class RequestOnlyFilter implements ContainerRequestFilter, ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            append(requestContext, "request-only");
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle("X-Response-Only", "ran");
        }
    }

    public static class NestedFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            append(requestContext, "nested");
        }
    }

    @Introspected
    public static class NestedFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(new NestedFilter(), 150);
            return true;
        }
    }

    @Requires(property = "spec.name", value = "FeatureRegistrationTest")
    @Provider
    public static class RegisteringFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(new FilterA());
            context.register(new FilterB(), 50);
            // rejected: B is registered
            context.register(new FilterB(), 10);
            context.register(new RequestOnlyFilter(), ContainerRequestFilter.class);
            context.register(NestedFeature.class);
            context.property("feature.property", "value");
            return true;
        }
    }

    @Requires(property = "spec.name", value = "FeatureRegistrationTest")
    @Path("/feature-registration")
    public static class FeatureRegistrationResource {

        @Context
        Configuration configuration;

        @GET
        @Produces("text/plain")
        public String get(@HeaderParam("X-Order") String order) {
            Map<Class<?>, Integer> contracts = configuration.getContracts(RequestOnlyFilter.class);
            return order + " " + configuration.getProperty("feature.property") + " "
                + contracts.keySet().stream().map(Class::getSimpleName).toList();
        }
    }
}
