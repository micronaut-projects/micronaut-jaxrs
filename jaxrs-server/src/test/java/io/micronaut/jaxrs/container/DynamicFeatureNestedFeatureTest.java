package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A feature a dynamic feature registers is configured: the filter it registers runs for the
 * resource method (DynamicFeature.configure).
 */
@MicronautTest
@Property(name = "spec.name", value = "DynamicFeatureNestedFeatureTest")
class DynamicFeatureNestedFeatureTest {

    @Inject
    @Client("/api/dynamic-nested-feature")
    HttpClient client;

    @Test
    void nestedFeatureConfigured() {
        assertEquals("filtered", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    @Requires(property = "spec.name", value = "DynamicFeatureNestedFeatureTest")
    @Path("/dynamic-nested-feature")
    public static class NestedResource {
        @GET
        @Produces("text/plain")
        public String get(@HeaderParam("X-Nested") String value) {
            return value;
        }
    }

    @Requires(property = "spec.name", value = "DynamicFeatureNestedFeatureTest")
    @Provider
    public static class OuterFeature implements DynamicFeature {
        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            if (resourceInfo.getResourceClass() == NestedResource.class) {
                context.register(new InnerFeature());
            }
        }
    }

    static final class InnerFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(new NestedFilter());
            return true;
        }
    }

    static final class NestedFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.getHeaders().putSingle("X-Nested", "filtered");
        }
    }
}
