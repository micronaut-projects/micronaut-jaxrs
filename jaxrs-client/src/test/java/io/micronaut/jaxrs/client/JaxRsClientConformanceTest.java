package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.ResponseProcessingException;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Property(name = "spec.name", value = "JaxRsClient")
@MicronautTest
class JaxRsClientConformanceTest {

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
    void responseFiltersRunInTheReverseOrderOfTheirPriorities() {
        List<String> order = new CopyOnWriteArrayList<>();
        WebTarget target = target("hello")
            .register((ClientResponseFilter) (request, response) -> order.add("100"), 100)
            .register((ClientResponseFilter) (request, response) -> order.add("300"), 300)
            .register((ClientResponseFilter) (request, response) -> order.add("200"), 200);

        assertEquals("hello", target.request().get(String.class));
        assertEquals(List.of("300", "200", "100"), order);
    }

    @Test
    void writerInterceptorsRunInTheOrderOfTheirRegistrationPriorities() {
        RecordingInterceptor.ORDER.clear();
        // the priority of the registration overrides @Priority(100)
        String echoed = target("echo")
            .register(FirstInterceptor.class, 300)
            .register(SecondInterceptor.class)
            .request()
            .post(Entity.text("body"), String.class);

        assertEquals("text/plain|body", echoed);
        assertEquals(List.of("second", "first"), RecordingInterceptor.ORDER);
    }

    @Test
    void secondRegistrationOfAComponentClassIsIgnored() {
        CountingFeature.CONFIGURED.set(0);
        WebTarget target = target("hello")
            .register(CountingFeature.class)
            .register(CountingFeature.class)
            .register(new CountingFeature());

        assertEquals(1, CountingFeature.CONFIGURED.get());
        assertEquals(Set.of(CountingFeature.class), target.getConfiguration().getClasses());
        assertTrue(target.getConfiguration().getInstances().stream().noneMatch(CountingFeature.class::isInstance));

        AtomicInteger filtered = new AtomicInteger();
        ClientRequestFilter filter = context -> filtered.incrementAndGet();
        target.register(filter).register(filter, 10).register(filter.getClass());
        assertEquals("hello", target.request().get(String.class));
        assertEquals(1, filtered.get());
    }

    @Test
    void failuresOfAReceivedResponseAreResponseProcessingExceptions() throws Exception {
        WebTarget failingFilter = target("hello").register((ClientResponseFilter) (request, response) -> {
            throw new IOException("the filter failed");
        });
        ResponseProcessingException e = assertThrows(ResponseProcessingException.class, () -> failingFilter.request().get());
        assertNotNull(e.getResponse());
        assertEquals(200, e.getResponse().getStatus());
        assertEquals("the filter failed", e.getCause().getMessage());

        WebTarget failingReader = target("hello").register(FailingReader.class);
        e = assertThrows(ResponseProcessingException.class, () -> failingReader.request().get(Item.class));
        assertEquals(200, e.getResponse().getStatus());

        ExecutionException async = assertThrows(ExecutionException.class,
            () -> failingReader.request().async().get(Item.class).get(10, TimeUnit.SECONDS));
        assertInstanceOf(ResponseProcessingException.class, async.getCause());
    }

    @Test
    void responseFiltersRunOnAResponseOfTheApplicationAFilterAbortedWith() {
        Response aborted = new ApplicationResponse(Response.ok("aborted", MediaType.TEXT_PLAIN_TYPE).build());
        AtomicReference<String> seen = new AtomicReference<>();
        String body = target("hello")
            .register((ClientRequestFilter) context -> context.abortWith(aborted))
            .register((ClientResponseFilter) (request, response) -> {
                seen.set(response.getMediaType() + "|" + response.getStatus());
                response.getHeaders().putSingle("X-Filtered", "yes");
            })
            .request()
            .get(String.class);

        assertEquals("aborted", body);
        assertEquals("text/plain|200", seen.get());
    }

    @Test
    void entityOfTheHttpClientIsWrittenThroughTheReplacedEntityStream() {
        // no JAX-RS writer writes the item: the HTTP client's JSON writer does
        String echoed = target("gunzip")
            .register((ClientRequestFilter) context -> {
                context.getHeaders().putSingle("X-Encoding", "gzip");
                try {
                    context.setEntityStream(new GZIPOutputStream(context.getEntityStream()));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            })
            .request()
            .post(Entity.json(new JsonItem("a")), String.class);

        assertEquals("application/json|{\"name\":\"a\"}", echoed);
    }

    @Test
    void entityOfTheHttpClientIsWrittenThroughTheWriterInterceptors() {
        AtomicReference<Object> intercepted = new AtomicReference<>();
        String echoed = target("echo")
            .register((WriterInterceptor) context -> {
                intercepted.set(context.getEntity());
                context.proceed();
            })
            .request()
            .post(Entity.json(new JsonItem("b")), String.class);

        assertEquals(new JsonItem("b"), intercepted.get());
        assertEquals("application/json|{\"name\":\"b\"}", echoed);
    }

    @Test
    void nullValuesRemove() {
        WebTarget target = target("header/X-Test").queryParam("q", "1", "2").queryParam("r", "3");
        assertEquals("r=3", target.queryParam("q", (Object) null).getUri().getQuery());
        assertEquals("r=3", target.queryParam("q", (Object[]) null).getUri().getQuery());
        assertNull(target("hello").queryParam("q", (Object) null).getUri().getQuery());

        assertEquals("none", target.request().header("X-Test", "value").header("X-Test", null).get(String.class));
        MultivaluedMap<String, Object> headers = new jakarta.ws.rs.core.MultivaluedHashMap<>();
        headers.add("X-Test", null);
        headers.add("X-Test", "kept");
        assertEquals("kept", target.request().headers(headers).get(String.class));

        target.property("name", "value");
        assertEquals("value", target.getConfiguration().getProperty("name"));
        target.property("name", null);
        assertNull(target.getConfiguration().getProperty("name"));
        assertFalse(target.getConfiguration().getPropertyNames().contains("name"));
    }

    @Test
    void providersSelectTheWriterOfTheMediaType() {
        ProvidersFilter filter = new ProvidersFilter();
        String body = target("hello")
            .register(XItemWriter.class)
            .register(YItemWriter.class)
            .register(filter)
            .request()
            .get(String.class);

        assertEquals("hello", body);
        MessageBodyWriter<Item> y = filter.providers.getMessageBodyWriter(Item.class, Item.class, new Annotation[0], MediaType.valueOf("text/y"));
        assertInstanceOf(YItemWriter.class, y);
        MessageBodyWriter<Item> x = filter.providers.getMessageBodyWriter(Item.class, Item.class, new Annotation[0], MediaType.valueOf("text/x"));
        assertInstanceOf(XItemWriter.class, x);
    }

    @Test
    void eventSourceKeepsProcessingWhenAConsumerFails() throws Exception {
        WebTarget target = target("events")
            .register((ClientRequestFilter) context -> context.getHeaders().putSingle("Authorization", "Bearer token"));
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(2);
        try (SseEventSource source = SseEventSource.target(target).reconnectingEvery(1, TimeUnit.HOURS).build()) {
            source.register(event -> {
                throw new IllegalStateException("the consumer failed");
            });
            source.register(event -> {
                events.add(event.readData());
                received.countDown();
            });
            source.open();
            assertTrue(received.await(10, TimeUnit.SECONDS), events::toString);
        }
        assertEquals(List.of("one", "two"), events);
    }

    @Test
    void eventSourceCompletesOnNoContent() throws Exception {
        CompletableFuture<String> end = new CompletableFuture<>();
        try (SseEventSource source = SseEventSource.target(target("no-events")).reconnectingEvery(10, TimeUnit.MILLISECONDS).build()) {
            source.register(event -> end.complete("event"), error -> end.complete("error " + error), () -> end.complete("complete"));
            source.open();
            assertEquals("complete", end.get(10, TimeUnit.SECONDS));
            assertFalse(source.isOpen());
        }
    }

    @Test
    void eventSourceFailsOnAResponseThatIsNotAnEventStream() throws Exception {
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        try (SseEventSource source = SseEventSource.target(target("plain-events")).build()) {
            source.register(event -> error.complete(null), error::complete);
            source.open();
            Throwable failure = error.get(10, TimeUnit.SECONDS);
            assertInstanceOf(ProcessingException.class, failure);
            assertTrue(failure.getMessage().contains("text/plain"), failure::getMessage);
        }
    }

    @Test
    void eventSourceFailsOnUnavailableWithoutRetryAfter() throws Exception {
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        try (SseEventSource source = SseEventSource.target(target("unavailable")).reconnectingEvery(10, TimeUnit.MILLISECONDS).build()) {
            source.register(event -> error.complete(null), error::complete);
            source.open();
            assertInstanceOf(ServiceUnavailableException.class, error.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void eventSourceRetryAfter() {
        assertEquals(2000L, JaxRsSseEventSource.retryAfter("2"));
        assertNull(JaxRsSseEventSource.retryAfter(null));
        assertNull(JaxRsSseEventSource.retryAfter("soon"));
        assertNull(JaxRsSseEventSource.retryAfter("-1"));
        assertEquals(0L, JaxRsSseEventSource.retryAfter("Sun, 06 Nov 1994 08:49:37 GMT"));
    }

    @Test
    void eventSourceContract() {
        SseEventSource source = SseEventSource.target(target("events")).build();
        assertThrows(IllegalArgumentException.class, () -> source.register(null));
        assertThrows(IllegalArgumentException.class, () -> source.register(event -> { }, null));
        assertThrows(IllegalArgumentException.class, () -> source.register(event -> { }, error -> { }, null));
        source.close();
        assertThrows(IllegalStateException.class, source::open);
    }

    @Test
    void eventSourceReconnectsOnTheScheduledExecutorServiceAndCompletesWhenClosed() throws Exception {
        AtomicInteger scheduled = new AtomicInteger();
        ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                scheduled.incrementAndGet();
                return super.schedule(command, delay, unit);
            }
        };
        Client schedulingClient = ClientBuilder.newBuilder().scheduledExecutorService(scheduler).build();
        try {
            WebTarget target = schedulingClient.target(server.getURI()).path("client/events")
                .register((ClientRequestFilter) context -> context.getHeaders().putSingle("Authorization", "Bearer token"));
            CountDownLatch received = new CountDownLatch(2);
            AtomicInteger completed = new AtomicInteger();
            SseEventSource source = SseEventSource.target(target).reconnectingEvery(1, TimeUnit.HOURS).build();
            source.register(event -> received.countDown(), error -> { }, completed::incrementAndGet);
            source.open();
            assertTrue(received.await(10, TimeUnit.SECONDS));
            // the stream ended: a reconnect is pending
            long deadline = System.currentTimeMillis() + 10_000;
            while (scheduled.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, scheduled.get());
            assertEquals(0, completed.get());
            source.close();
            assertEquals(1, completed.get());
            source.close();
            assertEquals(1, completed.get());
        } finally {
            schedulingClient.close();
            scheduler.shutdownNow();
        }
    }

    @Serdeable
    public record JsonItem(String name) {
    }

    public record Item(String name) {
    }

    public abstract static class RecordingInterceptor implements WriterInterceptor {
        static final List<String> ORDER = new CopyOnWriteArrayList<>();

        private final String name;

        RecordingInterceptor(String name) {
            this.name = name;
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            ORDER.add(name);
            context.proceed();
        }
    }

    @Priority(100)
    public static final class FirstInterceptor extends RecordingInterceptor {
        public FirstInterceptor() {
            super("first");
        }
    }

    @Priority(200)
    public static final class SecondInterceptor extends RecordingInterceptor {
        public SecondInterceptor() {
            super("second");
        }
    }

    public static final class CountingFeature implements Feature {
        static final AtomicInteger CONFIGURED = new AtomicInteger();

        @Override
        public boolean configure(FeatureContext context) {
            CONFIGURED.incrementAndGet();
            return true;
        }
    }

    public static final class FailingReader implements MessageBodyReader<Item> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Item.class;
        }

        @Override
        public Item readFrom(Class<Item> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                             MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            throw new IOException("the reader failed");
        }
    }

    public abstract static class ItemWriter implements MessageBodyWriter<Item> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Item.class;
        }

        @Override
        public void writeTo(Item item, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(item.name().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Produces("text/x")
    public static final class XItemWriter extends ItemWriter {
    }

    @Produces("text/y")
    public static final class YItemWriter extends ItemWriter {
    }

    public static final class ProvidersFilter implements ClientRequestFilter {
        @Context
        Providers providers;

        @Override
        public void filter(ClientRequestContext requestContext) {
        }
    }

    /**
     * A response of the application, delegating to another one.
     */
    static final class ApplicationResponse extends Response {
        private final Response delegate;

        ApplicationResponse(Response delegate) {
            this.delegate = delegate;
        }

        @Override
        public int getStatus() {
            return delegate.getStatus();
        }

        @Override
        public StatusType getStatusInfo() {
            return delegate.getStatusInfo();
        }

        @Override
        public Object getEntity() {
            return delegate.getEntity();
        }

        @Override
        public <T> T readEntity(Class<T> entityType) {
            return delegate.readEntity(entityType);
        }

        @Override
        public <T> T readEntity(GenericType<T> entityType) {
            return delegate.readEntity(entityType);
        }

        @Override
        public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
            return delegate.readEntity(entityType, annotations);
        }

        @Override
        public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
            return delegate.readEntity(entityType, annotations);
        }

        @Override
        public boolean hasEntity() {
            return delegate.hasEntity();
        }

        @Override
        public boolean bufferEntity() {
            return delegate.bufferEntity();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public MediaType getMediaType() {
            return delegate.getMediaType();
        }

        @Override
        public Locale getLanguage() {
            return delegate.getLanguage();
        }

        @Override
        public int getLength() {
            return delegate.getLength();
        }

        @Override
        public Set<String> getAllowedMethods() {
            return delegate.getAllowedMethods();
        }

        @Override
        public Map<String, NewCookie> getCookies() {
            return delegate.getCookies();
        }

        @Override
        public EntityTag getEntityTag() {
            return delegate.getEntityTag();
        }

        @Override
        public Date getDate() {
            return delegate.getDate();
        }

        @Override
        public Date getLastModified() {
            return delegate.getLastModified();
        }

        @Override
        public URI getLocation() {
            return delegate.getLocation();
        }

        @Override
        public Set<Link> getLinks() {
            return delegate.getLinks();
        }

        @Override
        public boolean hasLink(String relation) {
            return delegate.hasLink(relation);
        }

        @Override
        public Link getLink(String relation) {
            return delegate.getLink(relation);
        }

        @Override
        public Link.Builder getLinkBuilder(String relation) {
            return delegate.getLinkBuilder(relation);
        }

        @Override
        public MultivaluedMap<String, Object> getMetadata() {
            return delegate.getMetadata();
        }

        @Override
        public MultivaluedMap<String, String> getStringHeaders() {
            return delegate.getStringHeaders();
        }

        @Override
        public String getHeaderString(String name) {
            return delegate.getHeaderString(name);
        }
    }
}
