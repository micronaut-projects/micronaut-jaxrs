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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dynamic feature a feature registers for another contract only does not configure the
 * resource methods (Configurable#register(Object, Class[])).
 */
@MicronautTest
@Property(name = "spec.name", value = "DynamicFeatureContractTest")
class DynamicFeatureContractTest {

    static final AtomicInteger CONFIGURED = new AtomicInteger();

    @Inject
    @Client("/api/dynamic-feature-contract")
    HttpClient client;

    @Test
    void aDynamicFeatureRegisteredAsAFilterOnlyDoesNotConfigure() {
        assertEquals("filtered", client.toBlocking().retrieve(HttpRequest.GET("/")));
        assertEquals(0, CONFIGURED.get());
    }

    public static class FilterAndDynamicFeature implements DynamicFeature, ContainerRequestFilter {
        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            CONFIGURED.incrementAndGet();
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.abortWith(Response.ok("filtered", MediaType.TEXT_PLAIN_TYPE).build());
        }
    }

    @Requires(property = "spec.name", value = "DynamicFeatureContractTest")
    @Provider
    public static class RegisteringFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(new FilterAndDynamicFeature(), ContainerRequestFilter.class);
            return true;
        }
    }

    @Requires(property = "spec.name", value = "DynamicFeatureContractTest")
    @Path("/dynamic-feature-contract")
    public static class FilteredResource {
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            return "unused";
        }
    }
}
