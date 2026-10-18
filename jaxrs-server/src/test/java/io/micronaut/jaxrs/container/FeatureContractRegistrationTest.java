package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A reader and writer a feature registers as a reader only reads, and does not write
 * (Configurable#register(Object, Class[])).
 */
@MicronautTest
@Property(name = "spec.name", value = "FeatureContractRegistrationTest")
class FeatureContractRegistrationTest {

    @Inject
    @Client("/api/feature-contract")
    HttpClient client;

    @Test
    void aReaderOnlyRegistrationDoesNotWrite() {
        assertEquals("fallback:get", client.toBlocking().retrieve(HttpRequest.GET("/")));
        assertEquals("fallback:dual:posted", client.toBlocking().retrieve(
            HttpRequest.POST("/", "posted").contentType("text/x-dual")));
    }

    public record Dual(String value) {
    }

    public static class DualProvider implements MessageBodyReader<Dual>, MessageBodyWriter<Dual> {

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Dual.class;
        }

        @Override
        public Dual readFrom(Class<Dual> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                             MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return new Dual("dual:" + new String(entityStream.readAllBytes(), StandardCharsets.UTF_8));
        }

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Dual.class;
        }

        @Override
        public void writeTo(Dual dual, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("dual-writer:" + dual.value()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "FeatureContractRegistrationTest")
    @Provider
    public static class FallbackWriter implements MessageBodyWriter<Object> {

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Dual.class;
        }

        @Override
        public void writeTo(Object dual, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("fallback:" + ((Dual) dual).value()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "FeatureContractRegistrationTest")
    @Provider
    public static class ReaderOnlyFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(new DualProvider(), MessageBodyReader.class);
            return true;
        }
    }

    @Requires(property = "spec.name", value = "FeatureContractRegistrationTest")
    @Path("/feature-contract")
    public static class DualResource {

        @GET
        @Produces("text/plain")
        public Dual get() {
            return new Dual("get");
        }

        @POST
        @Consumes("text/x-dual")
        @Produces("text/plain")
        public Dual post(Dual dual) {
            return dual;
        }
    }
}
