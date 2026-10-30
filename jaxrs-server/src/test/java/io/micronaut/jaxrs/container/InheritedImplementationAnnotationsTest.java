package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpRequest;
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

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A resource method implemented by a superclass without annotations inherits those of the
 * interface method the resource class implements (JAX-RS 3.6): a writer gets them (JAX-RS 4.2.2).
 */
@MicronautTest
@Property(name = "spec.name", value = "InheritedImplementationAnnotationsTest")
class InheritedImplementationAnnotationsTest {

    @Inject
    @Client("/api/inherited-implementation")
    HttpClient client;

    @Test
    void writerGetsTheAnnotationsOfTheInterfaceMethod() {
        assertEquals("GET present", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    @Introspected
    public record Payload(String value) {
    }

    public interface Api {
        @GET
        @Produces("text/plain")
        Payload get();
    }

    public static class Base {
        public Payload get() {
            return new Payload("base");
        }
    }

    @Requires(property = "spec.name", value = "InheritedImplementationAnnotationsTest")
    @Path("/inherited-implementation")
    public static class InheritedResource extends Base implements Api {
    }

    @Requires(property = "spec.name", value = "InheritedImplementationAnnotationsTest")
    @Provider
    @Produces("text/plain")
    public static class PayloadWriter implements MessageBodyWriter<Payload> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Payload.class;
        }

        @Override
        public void writeTo(Payload payload, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws java.io.IOException {
            boolean get = Arrays.stream(annotations).anyMatch(annotation -> annotation.annotationType() == GET.class);
            entityStream.write((get ? "GET present" : "GET missing").getBytes(StandardCharsets.UTF_8));
        }
    }
}
