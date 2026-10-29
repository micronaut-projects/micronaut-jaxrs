package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entity stream a request filter sets is closed once the response was written, not before:
 * the resource method may return it as its entity.
 */
@MicronautTest
@Property(name = "spec.name", value = "FilterReplacedStreamResponseTest")
class FilterReplacedStreamResponseTest {

    static volatile TrackedStream stream;

    @Inject
    @Client("/api/filter-replaced-stream")
    HttpClient client;

    @Test
    void theReturnedReplacementIsWrittenThenClosed() throws InterruptedException {
        String result = client.toBlocking().retrieve(HttpRequest.POST("/", "ignored").contentType(MediaType.TEXT_PLAIN_TYPE));
        assertEquals("replaced", result);
        for (int i = 0; i < 100 && !stream.closed; i++) {
            Thread.sleep(50);
        }
        assertTrue(stream.closed);
    }

    static final class TrackedStream extends ByteArrayInputStream {
        volatile boolean closed;

        TrackedStream() {
            super("replaced".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            return super.read(b, off, len);
        }

        @Override
        public synchronized byte[] readAllBytes() {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            return super.readAllBytes();
        }

        @Override
        public synchronized long transferTo(java.io.OutputStream out) throws IOException {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            return super.transferTo(out);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Requires(property = "spec.name", value = "FilterReplacedStreamResponseTest")
    @Provider
    public static class ReplacingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (requestContext.getUriInfo().getPath().endsWith("filter-replaced-stream")) {
                stream = new TrackedStream();
                requestContext.setEntityStream(stream);
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterReplacedStreamResponseTest")
    @Path("/filter-replaced-stream")
    public static class StreamResource {
        @POST
        @Consumes("text/plain")
        @Produces("text/plain")
        public InputStream post(InputStream entity) {
            return entity;
        }
    }
}
