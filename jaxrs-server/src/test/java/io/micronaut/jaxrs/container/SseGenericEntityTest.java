package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data of an event given as a {@link GenericEntity} of a supertype is written by a writer of
 * its class (JAX-RS, OutboundSseEvent#getType).
 */
@MicronautTest
@Property(name = "spec.name", value = "SseGenericEntityTest")
class SseGenericEntityTest {

    @Inject
    EmbeddedServer server;

    @Test
    void theWriterOfTheClassOfTheDataWritesIt() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(server.getURL() + "/api/sse-generic"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(response.body().contains("data: CUSTOM"), response.body());
        }
    }

    /**
     * The declared type of the data.
     */
    public static class Base {
        @Override
        public String toString() {
            return "FALLBACK";
        }
    }

    /**
     * The class of the data.
     */
    public static class Sub extends Base {
    }

    @Requires(property = "spec.name", value = "SseGenericEntityTest")
    @Provider
    @Produces("text/plain")
    public static class SubWriter implements MessageBodyWriter<Sub> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Sub.class;
        }

        @Override
        public void writeTo(Sub sub, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write("CUSTOM".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "SseGenericEntityTest")
    @Path("/sse-generic")
    public static class Resource {
        @GET
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void events(@Context SseEventSink sink, @Context Sse sse) throws IOException {
            try (sink) {
                sink.send(sse.newEventBuilder().data(new GenericEntity<Base>(new Sub()) {
                }).build()).toCompletableFuture().join();
            }
        }
    }
}
