package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The entity of a resource method read by the application's readers (JAX-RS 4.2.1).
 */
@MicronautTest
@Property(name = "spec.name", value = "EntityReaderTest")
class EntityReaderTest {

    static final String NO_READER = "abc/def";

    @Inject
    EmbeddedServer server;

    private HttpResponse<String> post(String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/entity-reader"))
            .POST(HttpRequest.BodyPublishers.ofString("content"));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void noContentTypeIsReadAsOctetStream() throws Exception {
        HttpResponse<String> response = post(null);
        assertEquals(200, response.statusCode());
        assertEquals("application/octet-stream;content", response.body());
    }

    @Test
    void contentTypeIsPassedToTheReader() throws Exception {
        HttpResponse<String> response = post("text/html");
        assertEquals(200, response.statusCode());
        assertEquals("text/html;content", response.body());
    }

    @Test
    void noReaderIsUnsupportedMediaType() throws Exception {
        HttpResponse<String> response = post(NO_READER);
        assertEquals(415, response.statusCode());
    }

    @Test
    void emptyNumberIsBadRequest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/entity-reader/number"))
            .header("Content-Type", "text/plain")
            .header("Accept", "*/*")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(400, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void applicationReaderOfAStandardTypeIsUsed() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/entity-reader/bytes"))
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString("content"))
            .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals("read by BytesReader", client.send(request, HttpResponse.BodyHandlers.ofString()).body());
        }
    }

    @Requires(property = "spec.name", value = "EntityReaderTest")
    @Provider
    public static class BytesReader implements MessageBodyReader<byte[]> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == byte[].class;
        }

        @Override
        public byte[] readFrom(Class<byte[]> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            entityStream.readAllBytes();
            return "read by BytesReader".getBytes(StandardCharsets.UTF_8);
        }
    }

    public record Entity(String value) {
    }

    @Requires(property = "spec.name", value = "EntityReaderTest")
    @Provider
    @Consumes(MediaType.WILDCARD)
    public static class EntityReader implements MessageBodyReader<Entity> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Entity.class && !MediaType.valueOf(NO_READER).equals(mediaType);
        }

        @Override
        public Entity readFrom(Class<Entity> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return new Entity(mediaType + ";" + new String(entityStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "EntityReaderTest")
    @Path("/entity-reader")
    public static class EntityResource {
        @POST
        public String read(Entity entity) {
            return entity.value();
        }

        @POST
        @Path("bytes")
        public String bytes(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @POST
        @Path("number")
        public String number(Integer number) {
            return String.valueOf(number);
        }
    }
}
