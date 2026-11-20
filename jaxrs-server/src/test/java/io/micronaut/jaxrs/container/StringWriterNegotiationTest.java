package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The media types a writer of the application produces for a type of the standard providers,
 * e.g. a {@code String}, are producible types of a resource method without {@code @Produces}
 * (JAX-RS 3.8): a request that accepts only them is answered with them.
 */
@MicronautTest
@Property(name = "spec.name", value = "StringWriterNegotiationTest")
class StringWriterNegotiationTest {

    @Inject
    @Client("/api/string-negotiation")
    HttpClient client;

    @Test
    void theTypeOfTheApplicationWriterIsNegotiated() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/").accept("text/*"), String.class);
        assertEquals(200, response.code());
        assertTrue(response.getContentType().orElseThrow().toString().startsWith("text/csv"));
        assertEquals("csv:value", response.body());
    }

    @Requires(property = "spec.name", value = "StringWriterNegotiationTest")
    @Path("/string-negotiation")
    public static class Resource {
        @GET
        public String get() {
            return "value";
        }
    }

    @Requires(property = "spec.name", value = "StringWriterNegotiationTest")
    @Provider
    @Produces("text/csv")
    public static class CsvWriter implements MessageBodyWriter<String> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == String.class && mediaType.isCompatible(MediaType.valueOf("text/csv"));
        }

        @Override
        public void writeTo(String value, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("csv:" + value).getBytes(StandardCharsets.UTF_8));
        }
    }
}
