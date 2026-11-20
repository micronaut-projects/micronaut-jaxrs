package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading a response as an {@code InputStream} hands the annotations of {@code readEntity} to
 * the reader interceptors and the readers, also once a response filter set the entity stream.
 */
@Property(name = "spec.name", value = "JaxRsClient")
@MicronautTest
class JaxRsClientStreamReaderAnnotationsTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Marker {
    }

    @Marker
    private static final class Marked {
    }

    @Inject
    EmbeddedServer server;

    @Test
    void theAnnotationsReachTheInterceptors() throws IOException {
        assertEquals("marked:hello", read(false));
    }

    @Test
    void theAnnotationsReachTheInterceptorsOnceAFilterSetTheStream() throws IOException {
        assertEquals("marked:hello", read(true));
    }

    private String read(boolean filter) throws IOException {
        Client client = ClientBuilder.newClient().register(MarkingInterceptor.class);
        if (filter) {
            client.register(StreamSettingFilter.class);
        }
        try (Response response = client.target(server.getURI()).path("client").path("hello").request().get()) {
            InputStream stream = response.readEntity(InputStream.class, Marked.class.getAnnotations());
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            client.close();
        }
    }

    public static class StreamSettingFilter implements ClientResponseFilter {
        @Override
        public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
            responseContext.setEntityStream(responseContext.getEntityStream());
        }
    }

    public static class MarkingInterceptor implements ReaderInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            boolean marked = Arrays.stream(context.getAnnotations()).anyMatch(annotation -> annotation.annotationType() == Marker.class);
            Object read = context.proceed();
            if (marked && read instanceof InputStream stream) {
                return new java.io.SequenceInputStream(new java.io.ByteArrayInputStream("marked:".getBytes(StandardCharsets.UTF_8)), stream);
            }
            return read;
        }
    }
}
