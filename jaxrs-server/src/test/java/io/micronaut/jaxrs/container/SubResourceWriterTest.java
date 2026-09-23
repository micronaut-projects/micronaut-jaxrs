package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
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

/**
 * The entity of a sub-resource method is written by the writer of the application.
 */
@MicronautTest
@Property(name = "spec.name", value = "SubResourceWriterTest")
class SubResourceWriterTest {

    @Inject
    @Client("/api/sub-writer")
    HttpClient client;

    @Test
    void writerOfTheApplicationWritesTheEntityOfASubResource() {
        assertEquals("written:value", client.toBlocking().retrieve(HttpRequest.POST("/sub", ""), String.class));
    }

    @Test
    void writerOfTheApplicationWritesTheEntityOfARootResource() {
        assertEquals("written:root", client.toBlocking().retrieve(HttpRequest.POST("/root", ""), String.class));
    }

    public record Written(String value) {
    }

    @Requires(property = "spec.name", value = "SubResourceWriterTest")
    @Provider
    public static class WrittenWriter implements MessageBodyWriter<Written> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Written.class;
        }

        @Override
        public void writeTo(Written written, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("written:" + written.value()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "SubResourceWriterTest")
    @Path("/sub-writer")
    public static class RootResource {
        @POST
        @Path("root")
        public Written root() {
            return new Written("root");
        }

        @Path("sub")
        public Sub sub() {
            return new Sub();
        }
    }

    public static class Sub {
        @POST
        public Written written() {
            return new Written("value");
        }
    }
}
