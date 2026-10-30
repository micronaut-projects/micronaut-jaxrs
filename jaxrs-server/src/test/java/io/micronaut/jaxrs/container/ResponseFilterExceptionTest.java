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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A {@link WebApplicationException} a response filter throws: its response with an entity is used
 * as is (JAX-RS 3.3.4), and after a mapped response it is a server error (JAX-RS 4.4).
 */
@MicronautTest
@Property(name = "spec.name", value = "ResponseFilterExceptionTest")
class ResponseFilterExceptionTest {

    @Inject
    @Client("/api/response-filter-exception")
    HttpClient client;

    @Test
    void theResponseOfTheExceptionIsNotMapped() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/ok"), String.class));
        assertEquals(409, e.getStatus().getCode());
        assertEquals("original-wae", e.getResponse().getBody(String.class).orElse(null));
    }

    @Test
    void anExceptionOfTheMappedResponseIsAServerError() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/fail"), String.class));
        assertEquals(500, e.getStatus().getCode());
    }

    /**
     * An exception of the resource.
     */
    public static class ResourceException extends RuntimeException {
    }

    @Requires(property = "spec.name", value = "ResponseFilterExceptionTest")
    @Provider
    public static class ResourceExceptionMapper implements ExceptionMapper<ResourceException> {
        @Override
        public Response toResponse(ResourceException exception) {
            return Response.status(422).entity("mapped").type(MediaType.TEXT_PLAIN_TYPE).build();
        }
    }

    @Requires(property = "spec.name", value = "ResponseFilterExceptionTest")
    @Provider
    public static class WrongMapper implements ExceptionMapper<WebApplicationException> {
        @Override
        public Response toResponse(WebApplicationException exception) {
            return Response.status(423).entity("wrong-mapper").type(MediaType.TEXT_PLAIN_TYPE).build();
        }
    }

    @Requires(property = "spec.name", value = "ResponseFilterExceptionTest")
    @Provider
    public static class ThrowingFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            int status = responseContext.getStatus();
            if (status == 200 || status == 422) {
                throw new WebApplicationException(Response.status(409)
                    .entity(status == 200 ? "original-wae" : "second").type(MediaType.TEXT_PLAIN_TYPE).build());
            }
        }
    }

    @Requires(property = "spec.name", value = "ResponseFilterExceptionTest")
    @Path("/response-filter-exception")
    @Produces(MediaType.TEXT_PLAIN)
    public static class FilteredResource {
        @GET
        @Path("/ok")
        public String ok() {
            return "ok";
        }

        @GET
        @Path("/fail")
        public String fail() {
            throw new ResourceException();
        }
    }
}
