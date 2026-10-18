package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code @Context} members of a provider instance a dynamic feature registers are injected
 * before it is used.
 */
@MicronautTest
@Property(name = "spec.name", value = "DynamicFeatureInjectionTest")
class DynamicFeatureInjectionTest {

    @Inject
    @Client("/api/dynamic-injection")
    HttpClient client;

    @Test
    void registeredInstanceIsInjected() {
        assertEquals("/dynamic-injection/a", client.toBlocking().retrieve(HttpRequest.GET("/a")));
        assertEquals("/dynamic-injection/b", client.toBlocking().retrieve(HttpRequest.GET("/b")));
    }

    @Requires(property = "spec.name", value = "DynamicFeatureInjectionTest")
    @Path("/dynamic-injection")
    public static class InjectionResource {
        @GET
        @Path("{id}")
        @Produces("text/plain")
        public String get(@HeaderParam("X-Path") String path) {
            return path;
        }
    }

    @Requires(property = "spec.name", value = "DynamicFeatureInjectionTest")
    @Provider
    public static class InjectionFeature implements DynamicFeature {
        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            if (resourceInfo.getResourceClass() == InjectionResource.class) {
                context.register(new UriFilter());
            }
        }
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class UriFilter implements ContainerRequestFilter {
        @Context
        public UriInfo uriInfo;

        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.getHeaders().putSingle("X-Path", uriInfo.getPath());
        }
    }
}
