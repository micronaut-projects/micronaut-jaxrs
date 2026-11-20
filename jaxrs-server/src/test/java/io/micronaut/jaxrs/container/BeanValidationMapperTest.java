package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An exception mapper of the application for {@code ValidationException} answers the violations
 * the JAX-RS runtime finds, before the default responses (JAX-RS 7.6) and the exception handler of
 * Micronaut Validation.
 */
@MicronautTest
@Property(name = "spec.name", value = "BeanValidationMapperTest")
class BeanValidationMapperTest {

    @Inject
    @Client("/api")
    HttpClient client;

    private String get(String uri) {
        try {
            return client.toBlocking().retrieve(HttpRequest.GET(uri));
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode() + " " + e.getResponse().getBody(String.class).orElse("");
        }
    }

    @Test
    void mapperOfTheApplication() {
        assertEquals("418 mapped 1 violation", get("/mapped/param"));
        assertEquals("418 mapped 1 violation", get("/mapped/return"));
        assertEquals("ok", get("/mapped/param?q=ok"));
    }

    @Requires(property = "spec.name", value = "BeanValidationMapperTest")
    @Provider
    public static class ValidationMapper implements ExceptionMapper<ValidationException> {
        @Override
        public Response toResponse(ValidationException exception) {
            int count = exception instanceof ConstraintViolationException violations ? violations.getConstraintViolations().size() : -1;
            return Response.status(418).entity("mapped " + count + " violation").type("text/plain").build();
        }
    }

    @Requires(property = "spec.name", value = "BeanValidationMapperTest")
    @Path("/mapped")
    @Produces("text/plain")
    public static class MappedResource {

        @GET
        @Path("param")
        public String param(@NotBlank @QueryParam("q") String q) {
            return q;
        }

        @GET
        @Path("return")
        public @NotNull String returnValue() {
            return null;
        }
    }
}
