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
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A {@link GenericEntity} returned by a resource method is written by a writer of its generic
 * type, an entity without a writer is a server error (JAX-RS 4.2.2).
 */
@MicronautTest
@Property(name = "spec.name", value = "GenericEntityWriterTest")
class GenericEntityWriterTest {

    @Inject
    @Client("/api/generic-writer")
    HttpClient client;

    @Test
    void writerOfTheGenericTypeWritesTheEntity() {
        assertEquals("written:a,b", client.toBlocking().retrieve(HttpRequest.GET("/").accept("text/x-written"), String.class));
    }

    @Test
    void entityWithoutWriterIsAServerError() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/unknown"), String.class));
        assertEquals(500, e.getStatus().getCode());
    }

    public record Written(String value) {
    }

    @Requires(property = "spec.name", value = "GenericEntityWriterTest")
    @Provider
    @Produces("text/x-written")
    public static class WrittenListWriter implements MessageBodyWriter<List<Written>> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return List.class.isAssignableFrom(type) && genericType instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] == Written.class;
        }

        @Override
        public void writeTo(List<Written> written, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("written:" + String.join(",", written.stream().map(Written::value).toList())).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "GenericEntityWriterTest")
    @Path("/generic-writer")
    public static class GenericResource {
        @GET
        @Produces("text/x-written")
        public GenericEntity<List<Written>> get() {
            return new GenericEntity<>(List.of(new Written("a"), new Written("b"))) {
            };
        }

        @GET
        @Path("unknown")
        public Response unknown() {
            return Response.ok(new Unwritten(), "application/x-unknown").build();
        }
    }

    /**
     * An entity no writer writes.
     */
    public static class Unwritten {
        @Override
        public String toString() {
            return "unwritten";
        }
    }
}
