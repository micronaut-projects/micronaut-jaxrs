package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Features register components that the annotation processors never saw, and dynamic features
 * register filters for the resource methods they choose.
 */
@MicronautTest
@Property(name = "spec.name", value = "FeatureTest")
class FeatureTest {

    @Inject
    @Client("/api/features")
    HttpClient client;

    @Inject
    ApplicationContext context;

    @Test
    void featureRegistersAPreMatchingFilterThatIsNotABean() {
        assertTrue(context.containsBean(StaticFilter.class), "registered as a bean");
        assertEquals("static", client.toBlocking().retrieve(HttpRequest.GET("/static")));
    }

    @Test
    void dynamicFeatureRegistersAResponseFilterForOneMethod() {
        assertEquals("dynamic", client.toBlocking().retrieve(HttpRequest.GET("/dynamic")));
        assertEquals("plain", client.toBlocking().retrieve(HttpRequest.GET("/plain")));
    }

    @Requires(property = "spec.name", value = "FeatureTest")
    @Path("/features")
    public static class FeatureResource {
        @GET
        @Path("static")
        public String staticFeature() {
            return "not filtered";
        }

        @GET
        @Path("dynamic")
        public String dynamic() {
            return "not filtered";
        }

        @GET
        @Path("plain")
        public String plain() {
            return "plain";
        }
    }

    @Requires(property = "spec.name", value = "FeatureTest")
    @Provider
    public static class StaticFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(StaticFilter.class);
            return true;
        }
    }

    @Requires(property = "spec.name", value = "FeatureTest")
    @Provider
    public static class MethodFeature implements DynamicFeature {
        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            if (resourceInfo.getResourceMethod().getName().equals("dynamic")) {
                context.register(DynamicFilter.class);
            }
        }
    }
}

/**
 * Not a bean: registered by a feature.
 */
@PreMatching
class StaticFilter implements ContainerRequestFilter {
    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (requestContext.getUriInfo().getRequestUri().toString().endsWith("/static")) {
            requestContext.abortWith(Response.ok("static").build());
        }
    }
}

/**
 * Not a bean: registered by a dynamic feature.
 */
class DynamicFilter implements ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        responseContext.setEntity("dynamic");
    }
}
