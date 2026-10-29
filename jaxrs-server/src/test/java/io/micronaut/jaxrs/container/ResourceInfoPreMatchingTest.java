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
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@link ResourceInfo} of a request a pre-matching filter queried before the resource method
 * is matched has the matched resource method afterwards.
 */
@MicronautTest
@Property(name = "spec.name", value = "ResourceInfoPreMatchingTest")
class ResourceInfoPreMatchingTest {

    @Inject
    @Client("/api/resource-info-pre-matching")
    HttpClient client;

    @Test
    void theMatchedMethodAfterAPreMatchingQuery() {
        assertEquals("none:get", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    @Requires(property = "spec.name", value = "ResourceInfoPreMatchingTest")
    @Provider
    @PreMatching
    public static class QueryingFilter implements ContainerRequestFilter {
        @Context
        ResourceInfo resourceInfo;

        @Override
        public void filter(ContainerRequestContext requestContext) {
            Method method = resourceInfo.getResourceMethod();
            requestContext.getHeaders().putSingle("X-Before", method == null ? "none" : method.getName());
        }
    }

    @Requires(property = "spec.name", value = "ResourceInfoPreMatchingTest")
    @Path("/resource-info-pre-matching")
    public static class InfoResource {
        @GET
        @Produces("text/plain")
        public String get(@Context ResourceInfo resourceInfo, @HeaderParam("X-Before") String before) {
            Method method = resourceInfo.getResourceMethod();
            return before + ":" + (method == null ? "none" : method.getName());
        }
    }
}
