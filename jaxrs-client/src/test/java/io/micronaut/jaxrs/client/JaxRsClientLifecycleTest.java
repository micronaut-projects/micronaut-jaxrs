package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.ResponseProcessingException;
import jakarta.ws.rs.client.RxInvoker;
import jakarta.ws.rs.client.RxInvokerProvider;
import jakarta.ws.rs.client.SyncInvoker;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle of the streams and responses of the client on its failure and cancellation paths,
 * and the contracts of its API.
 */
@Property(name = "spec.name", value = "JaxRsClient")
@MicronautTest
class JaxRsClientLifecycleTest {

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
    void replacedEntityStreamIsClosedWhenTheWriterFails() {
        AtomicBoolean closed = new AtomicBoolean();
        WebTarget target = target("echo")
            .register(new FailingWriter())
            .register((ClientRequestFilter) context -> context.setEntityStream(new FilterOutputStream(context.getEntityStream()) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            }));

        ProcessingException e = assertThrows(ProcessingException.class,
            () -> target.request().post(Entity.entity(new Thing(), MediaType.TEXT_PLAIN_TYPE), String.class));
        assertInstanceOf(IOException.class, e.getCause());
        assertTrue(closed.get());
    }

    @Test
    void failureOfAResponseFilterCarriesTheFilteredResponse() {
        AtomicBoolean closed = new AtomicBoolean();
        WebTarget target = target("hello")
            .register((ClientResponseFilter) (request, response) -> {
                response.setEntityStream(new FilterInputStream(response.getEntityStream()) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                });
                throw new IOException("filter failed");
            });

        ResponseProcessingException e = assertThrows(ResponseProcessingException.class, () -> target.request().get(String.class));
        e.getResponse().close();
        assertTrue(closed.get());
    }

    @Test
    void responseOfAnInvocationCancelledWhileItIsProcessedIsClosed() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean called = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        WebTarget target = target("hello")
            .register((ClientResponseFilter) (request, response) -> {
                response.setEntityStream(new FilterInputStream(response.getEntityStream()) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                });
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

        Future<Response> future = target.request().async().get(new InvocationCallback<Response>() {
            @Override
            public void completed(Response response) {
                called.set(true);
            }

            @Override
            public void failed(Throwable throwable) {
                called.set(true);
            }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertTrue(future.cancel(true));
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!closed.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(closed.get());
        assertFalse(called.get());
    }

    @Test
    void streamOfAResponseFilterIsReadThroughTheReaderInterceptors() throws IOException {
        WebTarget target = target("hello")
            .register((ReaderInterceptor) context -> {
                context.proceed();
                return new ByteArrayInputStream("intercepted".getBytes(StandardCharsets.UTF_8));
            })
            .register((ClientResponseFilter) (request, response) ->
                response.setEntityStream(new FilterInputStream(response.getEntityStream()) { }));

        try (Response response = target.request().get(); InputStream stream = response.readEntity(InputStream.class)) {
            assertEquals("intercepted", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void zeroConnectTimeoutIsInfinite() {
        try (Client built = ClientBuilder.newBuilder().connectTimeout(0, TimeUnit.SECONDS).build()) {
            HttpClient httpClient = ((JaxRsClient) built).getHttpClient();
            assertEquals(Optional.of(Duration.ZERO), ((DefaultHttpClient) httpClient).getConfiguration().getConnectTimeout());
        }
    }

    @Test
    void propertyNamesOfAConfigurationCannotBeChanged() {
        WebTarget target = target("hello").property("x", "y");
        assertThrows(UnsupportedOperationException.class, () -> target.getConfiguration().getPropertyNames().clear());
        assertEquals("y", target.getConfiguration().getProperty("x"));
    }

    @Test
    void nullCacheControlRemovesIt() {
        CacheControl cacheControl = new CacheControl();
        cacheControl.setNoStore(true);
        assertEquals("none", target("header/Cache-Control").request().cacheControl(cacheControl).cacheControl(null).get(String.class));
        CacheControl other = new CacheControl();
        other.setMaxAge(5);
        assertEquals("no-transform,max-age=5", target("header/Cache-Control").request().cacheControl(cacheControl).cacheControl(other)
            .get(String.class).replace(" ", ""));
    }

    @Test
    void nullPropertyValueRemovesTheProperty() {
        AtomicBoolean requestHas = new AtomicBoolean(true);
        AtomicBoolean interceptorHas = new AtomicBoolean(true);
        String echoed = target("echo")
            .register((ClientRequestFilter) context -> {
                context.setProperty("x", "y");
                context.setProperty("x", null);
                requestHas.set(context.hasProperty("x") || context.getPropertyNames().contains("x"));
            })
            .register((WriterInterceptor) context -> {
                context.setProperty("z", "y");
                context.setProperty("z", null);
                interceptorHas.set(context.getPropertyNames().contains("z"));
                context.proceed();
            })
            .request()
            .post(Entity.text("body"), String.class);
        assertEquals("text/plain|body", echoed);
        assertFalse(requestHas.get());
        assertFalse(interceptorHas.get());
    }

    @Test
    void sslSettersRejectNull() throws Exception {
        ClientBuilder builder = ClientBuilder.newBuilder();
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        assertThrows(NullPointerException.class, () -> builder.sslContext(null));
        assertThrows(NullPointerException.class, () -> builder.trustStore(null));
        assertThrows(NullPointerException.class, () -> builder.keyStore(null, new char[0]));
        assertThrows(NullPointerException.class, () -> builder.keyStore(keyStore, (char[]) null));
    }

    @Test
    void sslAccessorsOfAClosedClientThrow() {
        Client closed = ClientBuilder.newClient();
        closed.close();
        assertThrows(IllegalStateException.class, closed::getSslContext);
        assertThrows(IllegalStateException.class, closed::getHostnameVerifier);
    }

    @Test
    void variantOfTheEntityReplacesTheHeadersOfTheBuilder() {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        String echoed = target("echo")
            .register((ClientRequestFilter) context -> seen.set(List.of(
                String.valueOf(context.getStringHeaders().get("Content-Type")),
                String.valueOf(context.getStringHeaders().get("Content-Language")),
                String.valueOf(context.getStringHeaders().get("Content-Encoding")))))
            .request()
            .header("Content-Type", "application/json")
            .header("Content-Language", "fr")
            .header("Content-Encoding", "br")
            .post(Entity.entity("body", new Variant(MediaType.TEXT_PLAIN_TYPE, Locale.ENGLISH, "identity")), String.class);
        assertEquals("text/plain|body", echoed);
        assertEquals(List.of("[text/plain]", "[en]", "[identity]"), seen.get());
    }

    @Test
    void sharedRxInvokerProviderSeesTheConfigurationOfTheInvocation() {
        var provider = new RecordingRxInvokerProvider();
        client.register(provider);
        target("hello").property("x", "A").request().rx(RecordingRxInvoker.class);
        target("hello").property("x", "B").request().rx(RecordingRxInvoker.class);
        assertEquals(List.of("A", "B"), provider.seen);
    }

    public static final class RecordingRxInvokerProvider implements RxInvokerProvider<RecordingRxInvoker> {
        final List<Object> seen = new CopyOnWriteArrayList<>();

        @Context
        Configuration configuration;

        @Override
        public boolean isProviderFor(Class<?> clazz) {
            return clazz == RecordingRxInvoker.class;
        }

        @Override
        public RecordingRxInvoker getRxInvoker(SyncInvoker syncInvoker, ExecutorService executorService) {
            seen.add(configuration.getProperty("x"));
            return null;
        }
    }

    public interface RecordingRxInvoker extends RxInvoker<Object> {
    }

    @Test
    void failureOfAReaderIsAProcessingException() {
        try (Response response = target("hello").register(new FailingReader()).request().get()) {
            ProcessingException e = assertThrows(ProcessingException.class, () -> response.readEntity(Thing.class));
            assertInstanceOf(IOException.class, e.getCause());
        }
    }

    static final class FailingReader implements MessageBodyReader<Thing> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public Thing readFrom(Class<Thing> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            throw new IOException("reader failed");
        }
    }

    @Test
    void causeOfAReadTimeoutIsATimeoutException() {
        try (Client timed = ClientBuilder.newBuilder().readTimeout(50, TimeUnit.MILLISECONDS).build()) {
            ProcessingException e = assertThrows(ProcessingException.class,
                () -> timed.target(server.getURI()).path("client/slow").request().get(String.class));
            assertInstanceOf(TimeoutException.class, e.getCause());
        }
    }

    private static OutputStream tracking(OutputStream stream, AtomicBoolean closed) {
        return new FilterOutputStream(stream) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
    }

    private static InputStream tracking(InputStream stream, AtomicBoolean closed) {
        return new FilterInputStream(stream) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
    }

    @Test
    void replacedEntityStreamIsClosedWhenALaterFilterFails() {
        AtomicBoolean closed = new AtomicBoolean();
        WebTarget target = target("echo")
            .register((ClientRequestFilter) context -> context.setEntityStream(tracking(context.getEntityStream(), closed)), 1)
            .register((ClientRequestFilter) context -> {
                throw new IOException("filter failed");
            }, 2);

        ProcessingException e = assertThrows(ProcessingException.class, () -> target.request().post(Entity.text("body"), String.class));
        assertInstanceOf(IOException.class, e.getCause());
        assertTrue(closed.get());
    }

    @Test
    void replacedEntityStreamIsClosedWhenNoWriterWritesTheEntity() {
        AtomicBoolean closed = new AtomicBoolean();
        WebTarget target = target("echo")
            .register((ClientRequestFilter) context -> context.setEntityStream(tracking(context.getEntityStream(), closed)));

        assertThrows(ProcessingException.class,
            () -> target.request().post(Entity.entity(new Thing(), "application/x-unknown"), String.class));
        assertTrue(closed.get());
    }

    @Test
    void streamOfAWriterInterceptorIsClosedWhenItFails() {
        AtomicBoolean closed = new AtomicBoolean();
        WebTarget target = target("echo")
            .register((WriterInterceptor) context -> {
                context.setOutputStream(tracking(context.getOutputStream(), closed));
                throw new IOException("interceptor failed");
            });

        assertThrows(ProcessingException.class, () -> target.request().post(Entity.text("body"), String.class));
        assertTrue(closed.get());
    }

    @Test
    void streamOfAReaderInterceptorIsClosedWithTheResponse() throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        WebTarget target = target("hello")
            .register((ReaderInterceptor) context -> {
                context.setInputStream(tracking(context.getInputStream(), closed));
                return context.proceed();
            });

        Response response = target.request().get();
        InputStream stream = response.readEntity(InputStream.class);
        assertEquals('h', stream.read());
        response.close();
        assertTrue(closed.get());
    }

    @Test
    void streamsOfAReaderInterceptorAreClosedWithTheReaderRead() throws IOException {
        AtomicBoolean firstClosed = new AtomicBoolean();
        AtomicBoolean secondClosed = new AtomicBoolean();
        WebTarget target = target("hello")
            .register((ReaderInterceptor) context -> {
                context.setInputStream(tracking(context.getInputStream(), firstClosed));
                // a second stream, independent of the first one
                context.setInputStream(tracking(new ByteArrayInputStream("other".getBytes(StandardCharsets.UTF_8)), secondClosed));
                return context.proceed();
            });

        Response response = target.request().get();
        java.io.Reader reader = response.readEntity(java.io.Reader.class);
        assertEquals('o', reader.read());
        reader.close();
        response.close();
        assertTrue(secondClosed.get());
        assertTrue(firstClosed.get());
    }

    @Test
    void streamOfAResponseFilterIsReadByTheReaderOfStreams() throws IOException {
        WebTarget target = target("hello")
            .register(new CustomStreamReader())
            .register((ClientResponseFilter) (request, response) ->
                response.setEntityStream(new FilterInputStream(response.getEntityStream()) { }));

        try (Response response = target.request().get(); InputStream stream = response.readEntity(InputStream.class)) {
            assertEquals("CUSTOM", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // more specific than the standard reader of streams, the tests being in a package of Micronaut
    @Consumes(MediaType.TEXT_PLAIN)
    public static final class CustomStreamReader implements MessageBodyReader<InputStream> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == InputStream.class;
        }

        @Override
        public InputStream readFrom(Class<InputStream> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                                    MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
            return new ByteArrayInputStream("CUSTOM".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void entityOfAnAbortedResponseIsNotIntercepted() {
        String read = target("hello")
            .register((ClientRequestFilter) context -> context.abortWith(Response.ok("cached", MediaType.TEXT_PLAIN_TYPE).build()))
            .register((WriterInterceptor) context -> {
                context.getOutputStream().write("outbound:".getBytes(StandardCharsets.UTF_8));
                context.proceed();
            })
            .request()
            .get(String.class);
        assertEquals("cached", read);
    }

    @Test
    void variantOfTheEntityRemovesTheEncodingAndLanguageItHasNot() {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        target("echo")
            .register((ClientRequestFilter) context -> {
                seen.set(List.of(
                    String.valueOf(context.getHeaderString("Content-Language")),
                    String.valueOf(context.getHeaderString("Content-Encoding"))));
                context.abortWith(Response.ok().build());
            })
            .request()
            .header("Content-Language", "fr")
            .header("Content-Encoding", "gzip")
            .post(Entity.text("hello"))
            .close();
        assertEquals(List.of("null", "null"), seen.get());
    }

    @Test
    void failureOfAReaderOtherThanAnIOExceptionIsAProcessingException() {
        try (Response response = target("hello").register(new RejectingReader()).request().get()) {
            ProcessingException e = assertThrows(ProcessingException.class, () -> response.readEntity(Thing.class));
            assertInstanceOf(IllegalArgumentException.class, e.getCause());
        }
    }

    static final class RejectingReader implements MessageBodyReader<Thing> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public Thing readFrom(Class<Thing> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
            throw new IllegalArgumentException("malformed");
        }
    }

    @Test
    void methodKeepsItsCase() {
        AtomicReference<String> seen = new AtomicReference<>();
        target("echo")
            .register((ClientRequestFilter) context -> {
                seen.set(context.getMethod());
                context.abortWith(Response.ok().build());
            })
            .request()
            .method("pOsT", Entity.text("hello"))
            .close();
        assertEquals("pOsT", seen.get());
    }

    @Test
    void classesAndInstancesOfAConfigurationCannotBeChanged() {
        WebTarget target = target("hello").register(CustomStreamReader.class).register(new RejectingReader());
        assertThrows(UnsupportedOperationException.class, () -> target.getConfiguration().getClasses().clear());
        assertThrows(UnsupportedOperationException.class, () -> target.getConfiguration().getInstances().clear());
    }

    @Test
    void providerRegisteredAsAClassIsConstructedWithItsContext() {
        assertEquals("A", target("hello").property("x", "A").register(ContextConstructedFilter.class).request().get(String.class));
        assertEquals("B", target("hello").property("x", "B").register(TwoConstructorsFilter.class).request().get(String.class));
    }

    public static final class ContextConstructedFilter implements ClientRequestFilter {
        private final Configuration configuration;

        public ContextConstructedFilter(@Context Configuration configuration) {
            this.configuration = configuration;
        }

        @Override
        public void filter(ClientRequestContext requestContext) {
            requestContext.abortWith(Response.ok(String.valueOf(configuration.getProperty("x"))).build());
        }
    }

    public static final class TwoConstructorsFilter implements ClientRequestFilter {
        private final @Nullable Configuration configuration;

        public TwoConstructorsFilter() {
            this.configuration = null;
        }

        public TwoConstructorsFilter(@Context Configuration configuration) {
            this.configuration = configuration;
        }

        @Override
        public void filter(ClientRequestContext requestContext) {
            requestContext.abortWith(Response.ok(configuration == null ? "none" : String.valueOf(configuration.getProperty("x"))).build());
        }
    }

    static final class Thing {
    }

    static final class FailingWriter implements MessageBodyWriter<Thing> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public void writeTo(Thing thing, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            throw new IOException("writer failed");
        }
    }
}
