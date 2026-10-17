package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Container filters that replace the entity with one of another type, and that abort with a
 * {@link Response} of the application.
 */
@MicronautTest
@Property(name = "spec.name", value = "FilterEntityTest")
class FilterEntityTest {

    @Inject
    @Client("/api/filter-entity")
    HttpClient client;

    @Test
    void entityOfAnotherType() {
        assertEquals("replaced", client.toBlocking().retrieve(HttpRequest.GET("/replace")));
    }

    @Test
    void abortWithResponseOfTheApplication() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/abort")));
        assertEquals(418, e.code());
    }

    @Test
    void preMatchingAbortWithResponseOfTheApplication() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/abort-pre-matching")));
        assertEquals(419, e.code());
    }

    @Requires(property = "spec.name", value = "FilterEntityTest")
    @Provider
    public static class ReplacingFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            if (requestContext.getUriInfo().getPath().endsWith("/replace")) {
                responseContext.setEntity("replaced".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterEntityTest")
    @Provider
    public static class AbortingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (requestContext.getUriInfo().getPath().endsWith("/abort")) {
                requestContext.abortWith(new ExceptionStatusTest.DelegatingResponse(Response.status(418).build()));
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterEntityTest")
    @Provider
    @PreMatching
    public static class PreMatchingAbortingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (requestContext.getUriInfo().getPath().endsWith("/abort-pre-matching")) {
                requestContext.abortWith(new ExceptionStatusTest.DelegatingResponse(Response.status(419).build()));
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterEntityTest")
    @Path("/filter-entity")
    public static class FilterEntityResource {

        @GET
        @Path("/{name}")
        @Produces("text/plain")
        public String get() {
            return "original";
        }
    }
}
