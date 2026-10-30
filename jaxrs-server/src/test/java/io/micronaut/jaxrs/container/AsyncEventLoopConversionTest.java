package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The value of an asynchronous response completed on an event loop is not converted on it: the
 * writers may block (Micronaut never blocks an event loop).
 */
@MicronautTest
@Property(name = "spec.name", value = "AsyncEventLoopConversionTest")
class AsyncEventLoopConversionTest {

    static final EventLoop EVENT_LOOP = new DefaultEventLoop();

    @Inject
    @Client("/api/async-loop")
    HttpClient client;

    @AfterAll
    static void shutdown() {
        EVENT_LOOP.shutdownGracefully();
    }

    @Test
    void aStageCompletedOnAnEventLoop() {
        assertEquals("off-loop", client.toBlocking().retrieve(HttpRequest.GET("/stage"), String.class));
    }

    @Test
    void aResponseResumedOnAnEventLoop() {
        assertEquals("off-loop", client.toBlocking().retrieve(HttpRequest.GET("/suspended"), String.class));
    }

    /**
     * An entity written with whether it is written on the event loop.
     */
    public record Payload(String value) {
    }

    @Requires(property = "spec.name", value = "AsyncEventLoopConversionTest")
    @Provider
    @Produces("text/plain")
    public static class PayloadWriter implements MessageBodyWriter<Payload> {

        private volatile boolean selectedOnLoop;

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            if (EVENT_LOOP.inEventLoop()) {
                selectedOnLoop = true;
            }
            return type == Payload.class;
        }

        @Override
        public void writeTo(Payload payload, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write((selectedOnLoop || EVENT_LOOP.inEventLoop() ? "on-loop" : "off-loop").getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "AsyncEventLoopConversionTest")
    @Path("/async-loop")
    public static class Resource {

        @GET
        @Path("stage")
        @Produces("text/plain")
        public CompletionStage<Payload> stage() {
            CompletableFuture<Payload> future = new CompletableFuture<>();
            EVENT_LOOP.execute(() -> future.complete(new Payload("done")));
            return future;
        }

        @GET
        @Path("suspended")
        @Produces("text/plain")
        public void suspended(@Suspended AsyncResponse response) {
            EVENT_LOOP.execute(() -> response.resume(new Payload("done")));
        }
    }
}
