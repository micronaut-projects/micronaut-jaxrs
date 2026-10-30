package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
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
 * The value of an asynchronous response completed by a thread of the application is written in
 * the context of its request: a writer can read it (JAX-RS 8.2, 10.1).
 */
@MicronautTest
@Property(name = "spec.name", value = "AsyncRequestContextTest")
class AsyncRequestContextTest {

    @Inject
    @Client("/api/async-context")
    HttpClient client;

    @Test
    void aStageCompletedByAnApplicationThread() {
        assertEquals("async-context/stage", client.toBlocking().retrieve(HttpRequest.GET("/stage"), String.class));
    }

    @Test
    void aResponseResumedByAnApplicationThread() {
        assertEquals("async-context/suspended", client.toBlocking().retrieve(HttpRequest.GET("/suspended"), String.class));
    }

    /**
     * An entity written with the path of its request.
     */
    public record Payload(String value) {
    }

    @Requires(property = "spec.name", value = "AsyncRequestContextTest")
    @Provider
    @Produces("text/plain")
    public static class PayloadWriter implements MessageBodyWriter<Payload> {

        @Context
        UriInfo uriInfo;

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Payload.class && uriInfo.getPath() != null;
        }

        @Override
        public void writeTo(Payload payload, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(uriInfo.getPath().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "AsyncRequestContextTest")
    @Path("/async-context")
    public static class Resource {

        @GET
        @Path("stage")
        @Produces("text/plain")
        public CompletionStage<Payload> stage() {
            CompletableFuture<Payload> future = new CompletableFuture<>();
            new Thread(() -> {
                sleep();
                future.complete(new Payload("done"));
            }).start();
            return future;
        }

        @GET
        @Path("suspended")
        @Produces("text/plain")
        public void suspended(@Suspended AsyncResponse response) {
            new Thread(() -> {
                sleep();
                response.resume(new Payload("done"));
            }).start();
        }

        private static void sleep() {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
