package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream a reader interceptor sets for an {@link InputStream} entity is closed by the runtime
 * once the request completes (JAX-RS ReaderInterceptorContext#setInputStream).
 */
@MicronautTest
@Property(name = "spec.name", value = "ReaderInterceptorStreamCloseTest")
class ReaderInterceptorStreamCloseTest {

    static final CountDownLatch CLOSED = new CountDownLatch(1);

    @Inject
    @Client("/api/reader-stream-close")
    HttpClient client;

    @Test
    void theStreamOfAStreamEntityIsClosedWhenTheRequestCompletes() throws InterruptedException {
        // read as bytes: the String reader of the client would run the interceptor too
        byte[] body = client.toBlocking().retrieve(HttpRequest.POST("/", "abc").contentType(MediaType.TEXT_PLAIN), byte[].class);
        assertEquals("abc", new String(body, StandardCharsets.UTF_8));
        assertTrue(CLOSED.await(10, TimeUnit.SECONDS));
    }

    @Requires(property = "spec.name", value = "ReaderInterceptorStreamCloseTest")
    @Provider
    @ConstrainedTo(RuntimeType.SERVER)
    public static class Interceptor implements ReaderInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            context.setInputStream(new FilterInputStream(context.getInputStream()) {
                @Override
                public void close() throws IOException {
                    CLOSED.countDown();
                    super.close();
                }
            });
            return context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "ReaderInterceptorStreamCloseTest")
    @Path("/reader-stream-close")
    public static class StreamResource {
        @POST
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        public String read(InputStream entity) throws IOException {
            // consumed, not closed
            return new String(entity.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
