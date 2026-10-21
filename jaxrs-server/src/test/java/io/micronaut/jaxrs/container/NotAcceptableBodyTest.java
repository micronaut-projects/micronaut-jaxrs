package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * When no acceptable type has a writer for the entity of a method without {@code @Produces}, the
 * response is not acceptable (JAX-RS, section 3.8), and when no writer writes it as the type
 * negotiated with the {@code @Produces} of the method, it is a server error (section 4.2.2, step 5),
 * unless a response filter repairs the response.
 */
@MicronautTest
@Property(name = "spec.name", value = "NotAcceptableBodyTest")
class NotAcceptableBodyTest {

    @Inject
    @Client("/api/not-acceptable-body")
    HttpClient client;

    @Test
    void noWriterForTheAcceptableTypes() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET("/any").accept("application/json;q=0, text/plain"), String.class));
        assertEquals(HttpStatus.NOT_ACCEPTABLE, e.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/json-or-text", "/text-only"})
    void noWriterForTheProducedType(String path) {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET(path).accept("application/json;q=0, text/plain"), String.class));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatus());
    }

    @Test
    void noWriterForTheProducedTypeWithoutAccept() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET("/text-only"), String.class));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatus());
    }

    @Test
    void writtenInTheAcceptedType() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/any").accept("text/plain;q=0.5, application/json"), String.class);
        assertEquals("application/json", response.getContentType().orElseThrow().toString());
        assertEquals("{\"name\":\"pojo\"}", response.body());
    }

    @Test
    void responseFilterReplacesTheEntityWithAWritableOne() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/text").accept("text/plain"), String.class);
        assertEquals("text/plain", response.getContentType().orElseThrow().toString());
        assertEquals("pojo", response.body());
    }

    @Test
    void responseFilterReplacesTheEntityOfAMethodWithoutProduces() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/any-text").accept("text/plain"), String.class);
        assertEquals("text/plain", response.getContentType().orElseThrow().toString());
        assertEquals("pojo", response.body());
    }

    @Test
    void responseFilterGivesTheSameEntityAWritableMediaType() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/json-repair").accept("*/*"), String.class);
        assertEquals("application/json", response.getContentType().orElseThrow().toString());
        assertEquals("{\"name\":\"pojo\"}", response.body());
    }

    @Serdeable
    public record Pojo(String name) {
    }

    @Requires(property = "spec.name", value = "NotAcceptableBodyTest")
    @Path("/not-acceptable-body")
    public static class NotAcceptableBodyResource {

        @GET
        @Path("/any")
        public Pojo any() {
            return new Pojo("pojo");
        }

        @GET
        @Path("/text")
        @Produces("text/plain")
        public Pojo text() {
            return new Pojo("pojo");
        }

        @GET
        @Path("/text-only")
        @Produces("text/plain")
        public Pojo textOnly() {
            return new Pojo("pojo");
        }

        @GET
        @Path("/any-text")
        public Pojo anyText() {
            return new Pojo("pojo");
        }

        @GET
        @Path("/json-repair")
        @Produces("text/plain")
        public Pojo jsonRepair() {
            return new Pojo("pojo");
        }

        @GET
        @Path("/json-or-text")
        @Produces({"application/json", "text/plain"})
        public Pojo jsonOrText() {
            return new Pojo("pojo");
        }
    }

    @Requires(property = "spec.name", value = "NotAcceptableBodyTest")
    @Provider
    public static class TextFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            String path = requestContext.getUriInfo().getPath();
            if ((path.endsWith("/text") || path.endsWith("/any-text")) && responseContext.getEntity() instanceof Pojo pojo) {
                responseContext.setEntity(pojo.name());
            } else if (path.endsWith("/json-repair")) {
                // the same entity, with a media type a writer writes it as
                responseContext.setEntity(responseContext.getEntity(), responseContext.getEntityAnnotations(), MediaType.APPLICATION_JSON_TYPE);
            }
        }
    }
}
