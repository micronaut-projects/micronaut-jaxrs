package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The value of an asynchronous resource method without {@code @Produces} is negotiated with the
 * types of its writers, like the result of a method that is not asynchronous (JAX-RS 3.8).
 */
@MicronautTest
@Property(name = "spec.name", value = "AsyncNegotiationTest")
class AsyncNegotiationTest {

    @Inject
    @Client("/api/async-negotiation")
    HttpClient client;

    @Test
    void negotiatedWithTheWriters() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/").accept("application/*"), String.class);
        assertEquals("custom async", response.body());
        assertEquals("application/x-custom", response.getContentType().orElseThrow().toString());
    }

    @Test
    void notAcceptable() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET("/").accept("text/html"), String.class));
        assertEquals(406, e.code());
    }

    public record Custom(String value) {
    }

    @Requires(property = "spec.name", value = "AsyncNegotiationTest")
    @Path("/async-negotiation")
    public static class AsyncResource {
        @GET
        public CompletionStage<Custom> get() {
            return CompletableFuture.supplyAsync(() -> new Custom("async"));
        }
    }

    @Requires(property = "spec.name", value = "AsyncNegotiationTest")
    @Provider
    @Produces("application/x-custom")
    public static class CustomWriter implements MessageBodyWriter<Custom> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Custom.class;
        }

        @Override
        public void writeTo(Custom custom, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("custom " + custom.value()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
