package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.sse.SseEventSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Regressions of the providers and the event sources of the client.
 */
@Property(name = "spec.name", value = "JaxRsClientRegression")
@MicronautTest
class JaxRsClientRegressionTest {

    @Inject
    EmbeddedServer server;

    Client client;

    @BeforeEach
    void setUp() {
        client = ClientBuilder.newClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void aProviderLookingUpAContextResolverInItsConstructorIsInstantiatedOnce() {
        ResolverLookingReader.instances.set(0);
        String read = client.target(server.getURI()).path("regression").path("text")
            .register(ResolverLookingReader.class)
            .request()
            .get(Thing.class).value;

        assertEquals("read:text", read);
        assertEquals(1, ResolverLookingReader.instances.get());
    }

    @Test
    void anEndlessErrorResponseOfAnEventSourceIsAnError() throws Exception {
        Throwable error = awaitError("endless-error");
        assertFalse(error instanceof OutOfMemoryError);
    }

    @Test
    void anErrorResponseWithAnUnknownStatusOfAnEventSourceIsAnError() throws Exception {
        Throwable error = awaitError("unknown-status");
        assertEquals(499, assertInstanceOf(WebApplicationException.class, error).getResponse().getStatus());
    }

    @Test
    void aSuccessfulResponseWithAnUnknownStatusOfAnEventSourceIsAnError() throws Exception {
        Throwable error = awaitError("unknown-success");
        assertEquals(299, assertInstanceOf(WebApplicationException.class, error).getResponse().getStatus());
    }

    @Test
    void aFilterLookingUpTheEntityProvidersInItsConstructorIsInstantiatedOnce() {
        ProviderLookingFilter.instances.set(0);
        String text = client.target(server.getURI()).path("regression").path("text")
            .register(ProviderLookingFilter.class)
            .request()
            .get(String.class);

        assertEquals("text", text);
        assertEquals(1, ProviderLookingFilter.instances.get());
    }

    public static class ProviderLookingFilter implements jakarta.ws.rs.client.ClientRequestFilter {
        static final AtomicInteger instances = new AtomicInteger();

        public ProviderLookingFilter(@Context Providers providers) {
            instances.incrementAndGet();
            providers.getMessageBodyReader(String.class, String.class, new Annotation[0], MediaType.TEXT_PLAIN_TYPE);
            providers.getMessageBodyWriter(String.class, String.class, new Annotation[0], MediaType.TEXT_PLAIN_TYPE);
        }

        @Override
        public void filter(jakarta.ws.rs.client.ClientRequestContext requestContext) {
        }
    }

    private Throwable awaitError(String path) throws Exception {
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        try (SseEventSource source = SseEventSource.target(client.target(server.getURI()).path("regression").path(path)).build()) {
            source.register(event -> { }, error::complete, () -> error.complete(null));
            source.open();
            Throwable failure = error.get(30, TimeUnit.SECONDS);
            assertFalse(source.isOpen());
            return failure;
        }
    }

    public static final class Thing {
        final String value;

        Thing(String value) {
            this.value = value;
        }
    }

    public static class ResolverLookingReader implements MessageBodyReader<Thing> {
        static final AtomicInteger instances = new AtomicInteger();

        public ResolverLookingReader(@Context Providers providers) {
            instances.incrementAndGet();
            providers.getContextResolver(String.class, MediaType.WILDCARD_TYPE);
        }

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public Thing readFrom(Class<Thing> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws java.io.IOException {
            return new Thing("read:" + new String(entityStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "JaxRsClientRegression")
    @Controller("/regression")
    static class RegressionController {

        @Get(value = "/text", produces = io.micronaut.http.MediaType.TEXT_PLAIN)
        String text() {
            return "text";
        }

        @Get(value = "/endless-error", produces = io.micronaut.http.MediaType.TEXT_EVENT_STREAM)
        HttpResponse<Flux<byte[]>> endlessError() {
            byte[] piece = new byte[64 * 1024];
            return HttpResponse.<Flux<byte[]>>serverError().body(Flux.<byte[]>generate(sink -> sink.next(piece)));
        }

        @Get(value = "/unknown-status", produces = io.micronaut.http.MediaType.TEXT_EVENT_STREAM)
        HttpResponse<String> unknownStatus() {
            return HttpResponse.<String>status(499, "Client Closed").body("closed");
        }

        @Get(value = "/unknown-success", produces = io.micronaut.http.MediaType.TEXT_EVENT_STREAM)
        HttpResponse<String> unknownSuccess() {
            return HttpResponse.<String>status(299, "Unknown").body("data: x\n\n");
        }
    }
}
