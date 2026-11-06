package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.codec.CodecException;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An unmapped {@link CodecException} is a client error only when reading the request entity
 * failed, and an exception thrown while the response mapped from an exception is processed is
 * not mapped again, by any mapper (JAX-RS 4.4).
 */
@MicronautTest
@Property(name = "spec.name", value = "ExceptionPhaseTest")
class ExceptionPhaseTest {

    @Inject
    @Client("/api/exception-phase")
    HttpClient client;

    @Test
    void codecExceptionOfTheResourceIsAServerError() {
        assertEquals(500, status(HttpRequest.GET("/codec")));
    }

    @Test
    void failureToWriteTheResponseIsAServerError() {
        assertEquals(500, status(HttpRequest.GET("/unwritable")));
    }

    @Test
    void failureToReadTheEntityIsAClientError() {
        assertEquals(400, status(HttpRequest.POST("/read", "{not json").contentType(MediaType.APPLICATION_JSON_TYPE)));
    }

    @Test
    void noContentExceptionOfTheResourceIsAServerError() {
        // a bad request only when a reader of the request entity throws it
        assertEquals(500, status(HttpRequest.GET("/no-content")));
    }

    @Test
    void emptyEntityOfAPrimitiveIsAClientError() {
        assertEquals(400, status(HttpRequest.POST("/int", "").contentType(MediaType.TEXT_PLAIN_TYPE)));
    }

    @Test
    void unmappedServerErrorDoesNotDiscloseTheExceptionMessage() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/secret")));
        assertEquals(500, e.code());
        String body = e.getResponse().getBody(String.class).orElse("");
        assertFalse(body.contains("hunter2"), body);
    }

    @Test
    void exceptionOfAMappedResponseIsNotMappedAgain() {
        assertEquals(500, status(HttpRequest.GET("/mapped")));
    }

    @Test
    void responseMappedFromAFilterFailureIsFiltered() {
        // a response filter fails, the response mapped from its exception is filtered (JAX-RS 4.4)
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/filter-failure")));
        assertEquals(422, e.code());
        assertEquals("true", e.getResponse().header("X-Filtered"));
    }

    private int status(HttpRequest<?> request) {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(request));
        return e.code();
    }

    public static final class FirstException extends RuntimeException {
    }

    public static final class SecondException extends RuntimeException {
    }

    @Requires(property = "spec.name", value = "ExceptionPhaseTest")
    @Provider
    public static class FirstMapper implements ExceptionMapper<FirstException> {
        @Override
        public Response toResponse(FirstException exception) {
            return Response.status(418).build();
        }
    }

    @Requires(property = "spec.name", value = "ExceptionPhaseTest")
    @Provider
    public static class SecondMapper implements ExceptionMapper<SecondException> {
        @Override
        public Response toResponse(SecondException exception) {
            return Response.status(419).build();
        }
    }

    /**
     * Fails processing the response the first mapper mapped.
     */
    @Requires(property = "spec.name", value = "ExceptionPhaseTest")
    @Provider
    public static class FailingFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            if (responseContext.getStatus() == 418) {
                throw new SecondException();
            }
        }
    }

    public static final class FilterException extends RuntimeException {
    }

    @Requires(property = "spec.name", value = "ExceptionPhaseTest")
    @Provider
    public static class FilterMapper implements ExceptionMapper<FilterException> {
        @Override
        public Response toResponse(FilterException exception) {
            return Response.status(422).build();
        }
    }

    /**
     * Fails processing the response of the resource method that is not mapped.
     */
    @Requires(property = "spec.name", value = "ExceptionPhaseTest")
    @Provider
    public static class FailingOnOkFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            if (responseContext.getStatus() == 200 && requestContext.getUriInfo().getPath().endsWith("filter-failure")) {
                throw new FilterException();
            }
        }
    }

    @Requires(property = "spec.name", value = "ExceptionPhaseTest")
    @Provider
    public static class MarkingFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            if (requestContext.getUriInfo().getPath().endsWith("filter-failure")) {
                responseContext.getHeaders().putSingle("X-Filtered", "true");
            }
        }
    }

    @Serdeable
    public record Item(String name) {
    }

    @Serdeable
    public static final class Unwritable {
        public String getName() {
            throw new IllegalStateException("not serializable");
        }
    }

    @Requires(property = "spec.name", value = "ExceptionPhaseTest")
    @Path("/exception-phase")
    public static class ExceptionPhaseResource {

        @GET
        @Path("codec")
        @Produces("text/plain")
        public String codec() {
            throw new CodecException("of the resource");
        }

        @GET
        @Path("unwritable")
        @Produces("application/json")
        public Unwritable unwritable() {
            return new Unwritable();
        }

        @POST
        @Path("read")
        @Consumes("application/json")
        @Produces("text/plain")
        public String read(Item item) {
            return item.name();
        }

        @GET
        @Path("filter-failure")
        @Produces("text/plain")
        public String filterFailure() {
            return "ok";
        }

        @GET
        @Path("mapped")
        @Produces("text/plain")
        public String mapped() {
            throw new FirstException();
        }

        @GET
        @Path("no-content")
        @Produces("text/plain")
        public String noContent() throws java.io.IOException {
            throw new jakarta.ws.rs.core.NoContentException("of the resource");
        }

        @GET
        @Path("secret")
        @Produces("text/plain")
        public String secret() {
            throw new IllegalStateException("jdbc:postgresql://db/secret?password=hunter2");
        }

        @POST
        @Path("int")
        @Consumes("text/plain")
        @Produces("text/plain")
        public String integer(int value) {
            return String.valueOf(value);
        }
    }
}
