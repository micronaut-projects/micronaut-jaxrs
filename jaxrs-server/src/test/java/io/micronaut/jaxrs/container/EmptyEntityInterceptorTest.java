package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An empty entity is read through the reader interceptors too (JAX-RS 6.3).
 */
@MicronautTest
@Property(name = "spec.name", value = "EmptyEntityInterceptorTest")
class EmptyEntityInterceptorTest {

    @Inject
    @Client("/api/empty-entity")
    HttpClient client;

    @Test
    void interceptorReplacesAnEmptyEntity() {
        assertEquals("[replaced]", client.toBlocking().retrieve(HttpRequest.POST("/string", "")
            .contentType(MediaType.TEXT_PLAIN).header("X-Replace", "replaced")));
        assertEquals("42", client.toBlocking().retrieve(HttpRequest.POST("/int", "")
            .contentType(MediaType.TEXT_PLAIN).header("X-Replace", "42")));
    }

    @Test
    void interceptorRejectsAnEmptyEntity() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking()
            .retrieve(HttpRequest.POST("/string", "").contentType(MediaType.TEXT_PLAIN).header("X-Reject", "true")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatus());
    }

    @Test
    void emptyEntityKeepsItsSemantics() {
        assertEquals("[]", client.toBlocking().retrieve(HttpRequest.POST("/string", "").contentType(MediaType.TEXT_PLAIN)));
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking()
            .retrieve(HttpRequest.POST("/int", "").contentType(MediaType.TEXT_PLAIN)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Requires(property = "spec.name", value = "EmptyEntityInterceptorTest")
    @Provider
    public static class Interceptor implements ReaderInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            if (context.getHeaders().containsKey("X-Reject")) {
                throw new WebApplicationException(422);
            }
            String replacement = context.getHeaders().getFirst("X-Replace");
            if (replacement != null) {
                context.setInputStream(new ByteArrayInputStream(replacement.getBytes(StandardCharsets.UTF_8)));
            }
            return context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "EmptyEntityInterceptorTest")
    @Path("/empty-entity")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public static class EntityResource {
        @POST
        @Path("/string")
        public String string(String entity) {
            return "[" + entity + "]";
        }

        @POST
        @Path("/int")
        public String integer(int entity) {
            return String.valueOf(entity);
        }
    }
}
