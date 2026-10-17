package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import jakarta.ws.rs.sse.InboundSseEvent;
import jakarta.ws.rs.sse.SseEventSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Property(name = "spec.name", value = "JaxRsClient")
@MicronautTest
class JaxRsClientTest {

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

    private WebTarget target(String path) {
        return client.target(server.getURI()).path("client").path(path);
    }

    @Test
    void interceptorContextsShareTheRequestProperties() {
        String result = target("echo")
            .register((ClientRequestFilter) requestContext -> requestContext.setProperty("filter", "filter"))
            .register(new WriterInterceptor() {
                @Override
                public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
                    context.setEntity(context.getEntity() + "|" + context.getProperty("filter"));
                    context.setProperty("writer", "writer");
                    context.proceed();
                }
            })
            .register(new ReaderInterceptor() {
                @Override
                public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
                    return context.proceed() + "|" + context.getProperty("filter") + "|" + context.getProperty("writer");
                }
            })
            .request()
            .post(Entity.text("body"), String.class);
        assertEquals("text/plain|body|filter|filter|writer", result);
    }



    @Test
    void registeredFeatureIsConfigured() {
        WebTarget target = target("header/X-Feature").register(HeaderFeature.class);

        assertTrue(target.getConfiguration().isEnabled(HeaderFeature.class));
        assertTrue(target.getConfiguration().isRegistered(HeaderFilter.class));
        assertEquals("feature", target.request().get(String.class));
    }

    @Test
    void featureThatDeclinesIsNotEnabled() {
        DecliningFeature feature = new DecliningFeature();
        WebTarget target = target("hello").register(feature);

        assertTrue(feature.configured);
        assertFalse(target.getConfiguration().isEnabled(feature));
        assertFalse(target.getConfiguration().isEnabled(DecliningFeature.class));
    }

    @Test
    void requestFiltersRunBeforeTheEntityIsWritten() {
        AtomicReference<Object> seen = new AtomicReference<>();
        String echoed = target("echo")
            .register(ItemWriter.class)
            .register((ClientRequestFilter) context -> {
                seen.set(context.getEntity());
                // a new entity and media type, which select the writer
                context.setEntity(new Item("replaced"), new Annotation[0], MediaType.valueOf("text/x-item"));
            })
            .request()
            .post(Entity.entity(new Item("original"), MediaType.TEXT_PLAIN_TYPE), String.class);

        assertEquals(new Item("original"), seen.get());
        assertEquals("text/x-item|item:replaced@text/x-item", echoed);
    }

    @Test
    void filterChangesTheContentTypeTheWriterSees() {
        String echoed = target("echo")
            .register(ItemWriter.class)
            .register((ClientRequestFilter) context -> context.getHeaders().putSingle(HttpHeaders.CONTENT_TYPE, "text/x-other"))
            .request()
            .post(Entity.entity(new Item("a"), MediaType.TEXT_PLAIN_TYPE), String.class);

        assertEquals("text/x-other|item:a@text/x-other", echoed);
    }

    @Test
    void nullEntities() {
        assertEquals("text/plain|", target("echo").request().post(Entity.entity(null, MediaType.TEXT_PLAIN_TYPE), String.class));

        AtomicReference<Boolean> hasEntity = new AtomicReference<>();
        String echoed = target("echo")
            .register((ClientRequestFilter) context -> {
                context.setEntity(null);
                hasEntity.set(context.hasEntity());
            })
            .request()
            .post(Entity.text("body"), String.class);
        assertFalse(hasEntity.get());
        assertEquals("text/plain|", echoed);

        echoed = target("echo")
            .register((ClientRequestFilter) context -> context.setEntity(null, null, null))
            .request()
            .post(Entity.text("body"), String.class);
        assertEquals("text/plain|", echoed);
    }

    @Test
    void responseFiltersRunForErrorResponsesOfTypedInvocations() {
        AtomicInteger filtered = new AtomicInteger();
        NotFoundException e = assertThrows(NotFoundException.class, () -> target("missing")
            .register(ErrorReader.class)
            .register((ClientResponseFilter) (request, response) -> filtered.incrementAndGet())
            .request()
            .get(String.class));

        assertEquals(1, filtered.get());
        assertEquals(new Error("the error"), e.getResponse().readEntity(Error.class));
    }

    @Test
    void responseFilterChangesTheStatusOfATypedInvocation() {
        String body = target("missing")
            .register((ClientResponseFilter) (request, response) -> response.setStatus(200))
            .request()
            .get(String.class);

        assertEquals("the error", body);
    }

    @Test
    void customMethod() {
        assertEquals("PURGE", target("method").request().method("PURGE", String.class));
        assertEquals("PURGE", target("method").request().build("PURGE").invoke(String.class));
    }

    @Test
    void requestPropertiesReachTheFilters() {
        ClientRequestFilter filter = context -> context.getHeaders().putSingle("X-Property", String.valueOf(context.getProperty("name")));
        WebTarget target = target("header/X-Property").register(filter);

        assertEquals("builder", target.request().property("name", "builder").get(String.class));
        assertEquals("invocation", target.request().buildGet().property("name", "invocation").invoke(String.class));
        assertEquals("null", target.request().property("name", "removed").property("name", null).get(String.class));
        assertNull(target.getConfiguration().getProperty("name"));
    }

    @Test
    void callbackFailsWithTheExceptionOfTheStatus() throws Exception {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        target("missing").request().async().get(new InvocationCallback<String>() {
            @Override
            public void completed(String response) {
                failure.complete(null);
            }

            @Override
            public void failed(Throwable throwable) {
                failure.complete(throwable);
            }
        });

        assertInstanceOf(NotFoundException.class, failure.get(10, TimeUnit.SECONDS));

        Throwable stageFailure = target("missing").request().rx().get(String.class)
            .handle((value, throwable) -> throwable)
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
        assertInstanceOf(NotFoundException.class, stageFailure);
    }

    @Test
    void callbacksRunOnTheExecutorService() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "jaxrs-test-executor"));
        Client executorClient = ClientBuilder.newBuilder().executorService(executor).build();
        try {
            WebTarget target = executorClient.target(server.getURI()).path("client").path("hello");
            CompletableFuture<String> result = new CompletableFuture<>();
            target.request().async().get(new InvocationCallback<String>() {
                @Override
                public void completed(String response) {
                    // a nested synchronous invocation does not deadlock
                    result.complete(Thread.currentThread().getName() + ":" + response + ":" + target.request().get(String.class));
                }

                @Override
                public void failed(Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });

            assertEquals("jaxrs-test-executor:hello:hello", result.get(10, TimeUnit.SECONDS));
            assertEquals("jaxrs-test-executor", target.request().rx().get(String.class)
                .thenApply(value -> Thread.currentThread().getName())
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS));
        } finally {
            executorClient.close();
            executor.shutdownNow();
        }
    }

    @Test
    void derivedTargetsHaveTheirOwnConfiguration() {
        WebTarget parent = target("header/X-Filter");
        WebTarget child = parent.path("child");
        WebTarget sibling = parent.queryParam("q", "v");
        ClientRequestFilter filter = context -> context.getHeaders().putSingle("X-Filter", "child");

        child.register(filter);

        assertTrue(child.getConfiguration().isRegistered(filter));
        assertFalse(parent.getConfiguration().isRegistered(filter));
        assertFalse(sibling.getConfiguration().isRegistered(filter));
        assertEquals("none", parent.request().get(String.class));
        // a target derived after the registration inherits it
        assertTrue(child.path("grandchild").getConfiguration().isRegistered(filter));
    }

    @Test
    void classComponentIsInstantiatedOnce() {
        CountingFilter.INSTANCES.set(0);
        CountingFilter.FILTERED.set(0);
        WebTarget target = target("hello").register(CountingFilter.class);

        for (int i = 0; i < 3; i++) {
            assertEquals("hello", target.request().get(String.class));
        }
        assertEquals("hello", target.path("").request().get(String.class));

        assertEquals(1, CountingFilter.INSTANCES.get());
        assertEquals(4, CountingFilter.FILTERED.get());
    }

    @Test
    void eventSourceRunsTheRequestFilters() throws Exception {
        WebTarget target = target("events")
            .register((ClientRequestFilter) context -> context.getHeaders().putSingle("Authorization", "Bearer token"));
        List<String> events = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(2);
        try (SseEventSource source = SseEventSource.target(target).build()) {
            source.register(event -> {
                events.add(event.readData());
                received.countDown();
            }, errors::add);
            source.open();
            assertTrue(received.await(10, TimeUnit.SECONDS), () -> "events " + events + ", errors " + errors);
        }
        assertEquals(List.of("one", "two"), events.subList(0, 2));
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void eventSourceUsesTheConfigurationOfTheClient() throws Exception {
        // without the filter, the server refuses the request
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        try (SseEventSource source = SseEventSource.target(target("events")).build()) {
            source.register((InboundSseEvent event) -> error.complete(null), error::complete);
            source.open();
            assertTrue(error.get(10, TimeUnit.SECONDS).getMessage().contains("Unauthorized"));
        }
    }

    public record Item(String name) {
    }

    public record Error(String message) {
    }

    public static final class ItemWriter implements MessageBodyWriter<Item> {

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Item.class;
        }

        @Override
        public void writeTo(Item item, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws java.io.IOException {
            entityStream.write(("item:" + item.name() + "@" + mediaType).getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final class ErrorReader implements MessageBodyReader<Error> {

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Error.class;
        }

        @Override
        public Error readFrom(Class<Error> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws java.io.IOException {
            return new Error(new String(entityStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public static final class HeaderFilter implements ClientRequestFilter {
        @Override
        public void filter(ClientRequestContext requestContext) {
            requestContext.getHeaders().putSingle("X-Feature", "feature");
        }
    }

    public static final class HeaderFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(HeaderFilter.class);
            return true;
        }
    }

    public static final class DecliningFeature implements Feature {
        volatile boolean configured;

        @Override
        public boolean configure(FeatureContext context) {
            configured = true;
            return false;
        }
    }

    public static final class CountingFilter implements ClientRequestFilter {
        static final AtomicInteger INSTANCES = new AtomicInteger();
        static final AtomicInteger FILTERED = new AtomicInteger();

        public CountingFilter() {
            INSTANCES.incrementAndGet();
        }

        @Override
        public void filter(ClientRequestContext requestContext) {
            FILTERED.incrementAndGet();
        }
    }
}
