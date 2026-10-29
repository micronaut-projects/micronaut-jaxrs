package example.providers;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A reader of the application is used in preference to the standard one that reads the same
 * entity (JAX-RS 4.2.4), even when the standard one declares a more specific media type.
 * Outside of the packages of Micronaut, whose providers are the standard ones.
 */
@MicronautTest
@Property(name = "spec.name", value = "ApplicationReaderPreferenceTest")
class ApplicationReaderPreferenceTest {

    @Inject
    @Client("/api/reader-preference")
    HttpClient client;

    @Test
    void theReaderOfTheApplicationOfAnyMediaTypePrecedesTheStandardOne() {
        // the standard reader of numbers declares text/plain, the one of the application none
        assertEquals("255", client.toBlocking().retrieve(
            HttpRequest.POST("/", "ff").contentType(MediaType.TEXT_PLAIN)));
    }

    @Requires(property = "spec.name", value = "ApplicationReaderPreferenceTest")
    @Provider
    public static class AnyTypeHexLongReader implements MessageBodyReader<Long> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Long.class;
        }

        @Override
        public Long readFrom(Class<Long> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                             MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return Long.parseLong(new String(entityStream.readAllBytes(), StandardCharsets.UTF_8), 16);
        }
    }

    @Requires(property = "spec.name", value = "ApplicationReaderPreferenceTest")
    @Path("/reader-preference")
    public static class PreferenceResource {
        @POST
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        public String read(Long value) {
            return String.valueOf(value);
        }
    }
}
