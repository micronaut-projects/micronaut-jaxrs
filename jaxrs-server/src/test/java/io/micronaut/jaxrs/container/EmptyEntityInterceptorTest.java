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
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
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

    @Test
    void interceptorChangesTheMediaTypeOfAnEmptyEntity() {
        assertEquals("empty", client.toBlocking().retrieve(HttpRequest.POST("/item", "")
            .contentType(MediaType.TEXT_PLAIN).header("X-Type", "application/x-item")));
    }

    @Test
    void readerOfTheApplicationReadsAnEmptyEntity() {
        assertEquals("-1", client.toBlocking().retrieve(HttpRequest.POST("/integer", "").contentType("text/x-int")));
    }

    /**
     * An entity of the application.
     *
     * @param name The name
     */
    public record Item(String name) {
    }

    @Requires(property = "spec.name", value = "EmptyEntityInterceptorTest")
    @Provider
    @Consumes("application/x-item")
    public static class ItemReader implements MessageBodyReader<Item> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Item.class;
        }

        @Override
        public Item readFrom(Class<Item> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                             MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            String name = new String(entityStream.readAllBytes(), StandardCharsets.UTF_8);
            return new Item(name.isEmpty() ? "empty" : name);
        }
    }

    @Requires(property = "spec.name", value = "EmptyEntityInterceptorTest")
    @Provider
    @Consumes("text/x-int")
    public static class IntReader implements MessageBodyReader<Integer> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Integer.class;
        }

        @Override
        public Integer readFrom(Class<Integer> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                                MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            String value = new String(entityStream.readAllBytes(), StandardCharsets.UTF_8);
            return value.isEmpty() ? -1 : Integer.parseInt(value);
        }
    }

    @Requires(property = "spec.name", value = "EmptyEntityInterceptorTest")
    @Provider
    public static class Interceptor implements ReaderInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            if (context.getHeaders().containsKey("X-Reject")) {
                throw new WebApplicationException(422);
            }
            String mediaType = context.getHeaders().getFirst("X-Type");
            if (mediaType != null) {
                context.setMediaType(MediaType.valueOf(mediaType));
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

        @POST
        @Path("/integer")
        @Consumes("text/x-int")
        public String boxed(Integer entity) {
            return String.valueOf(entity);
        }

        @POST
        @Path("/item")
        public String item(Item entity) {
            return entity.name();
        }
    }
}
