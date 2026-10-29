package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.exceptions.ConnectionClosedException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.ConnectionCallback;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-side asynchronous processing with a {@code @Suspended} {@link AsyncResponse} (JAX-RS 8.2).
 */
@MicronautTest
@Property(name = "spec.name", value = "AsyncResponseTest")
class AsyncResponseTest {

    static final BlockingQueue<Object> COMPLETED = new LinkedBlockingQueue<>();
    static final BlockingQueue<Object> STATES = new LinkedBlockingQueue<>();
    static final BlockingQueue<Object> DISCONNECTED = new LinkedBlockingQueue<>();

    @Inject
    EmbeddedServer server;

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/async-response/" + path)).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void resumeWithEntity() throws Exception {
        HttpResponse<String> response = get("entity");
        assertEquals(200, response.statusCode());
        assertEquals("resumed", response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
    }

    @Test
    void resumeWithResponse() throws Exception {
        HttpResponse<String> response = get("response");
        assertEquals(201, response.statusCode());
        assertEquals("created", response.body());
        assertEquals("value", response.headers().firstValue("X-Async").orElse(null));
    }

    @Test
    void resumeFromAnotherThread() throws Exception {
        HttpResponse<String> response = get("later");
        assertEquals(200, response.statusCode());
        assertEquals("later", response.body());
    }

    @Test
    void resumeWithMappedException() throws Exception {
        HttpResponse<String> response = get("mapped");
        assertEquals(418, response.statusCode());
        assertEquals("mapped: failed", response.body());
    }

    @Test
    void resumeWithWebApplicationException() throws Exception {
        assertEquals(404, get("not-found").statusCode());
    }

    @Test
    void thrownExceptionIsMapped() throws Exception {
        HttpResponse<String> response = get("thrown");
        assertEquals(418, response.statusCode());
        assertEquals("mapped: thrown", response.body());
    }

    @Test
    void timeoutIsServiceUnavailable() throws Exception {
        assertEquals(503, get("timeout").statusCode());
    }

    @Test
    void timeoutHandlerResumes() throws Exception {
        HttpResponse<String> response = get("timeout-handler");
        assertEquals(200, response.statusCode());
        assertEquals("timed out", response.body());
    }

    @Test
    void cancelIsServiceUnavailable() throws Exception {
        STATES.clear();
        HttpResponse<String> response = get("cancel");
        assertEquals(503, response.statusCode());
        assertEquals("5", response.headers().firstValue("Retry-After").orElse(null));
        assertEquals(List.of(true, true, false, true), STATES.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void completionCallbackIsCalled() throws Exception {
        COMPLETED.clear();
        assertEquals("completed", get("callback").body());
        assertEquals("none", COMPLETED.poll(5, TimeUnit.SECONDS));
        // a mapped exception completes with a response
        assertEquals(418, get("callback-failure").statusCode());
        assertEquals("none", COMPLETED.poll(5, TimeUnit.SECONDS));
        assertEquals(500, get("callback-unmapped").statusCode());
        assertInstanceOf(IllegalStateException.class, COMPLETED.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void completionCallbackIsCalledAfterTheResponseFilters() throws Exception {
        COMPLETED.clear();
        assertEquals("ordered", get("callback-order").body());
        assertEquals("filtered", COMPLETED.poll(5, TimeUnit.SECONDS));
        assertEquals("none", COMPLETED.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void completionCallbackIsCalledAfterTheWriter() throws Exception {
        COMPLETED.clear();
        assertEquals("written:value", get("callback-written").body());
        assertEquals("filtered", COMPLETED.poll(5, TimeUnit.SECONDS));
        assertEquals("written", COMPLETED.poll(5, TimeUnit.SECONDS));
        assertEquals("none", COMPLETED.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void completionCallbackReceivesTheFailureOfTheWriter() throws Exception {
        COMPLETED.clear();
        assertEquals(500, get("callback-writer-failure").statusCode());
        Object failure = COMPLETED.poll(5, TimeUnit.SECONDS);
        assertInstanceOf(IOException.class, failure);
        assertEquals("broken writer", ((IOException) failure).getMessage());
    }

    @Test
    void connectionCallbackIsCalledWhenTheClientDisconnects() throws Exception {
        COMPLETED.clear();
        DISCONNECTED.clear();
        STATES.clear();
        try (Socket socket = new Socket(server.getHost(), server.getPort())) {
            socket.getOutputStream().write(("GET /api/async-response/disconnect HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertEquals(List.of(jakarta.ws.rs.container.CompletionCallback.class, ConnectionCallback.class), STATES.poll(5, TimeUnit.SECONDS));
        }
        AsyncResponse disconnected = (AsyncResponse) DISCONNECTED.poll(5, TimeUnit.SECONDS);
        // the processing ended: the response is no longer suspended and cannot be resumed
        assertEquals(List.of(true, false, false, false), STATES.poll(5, TimeUnit.SECONDS));
        assertTrue(disconnected.isDone());
        assertFalse(disconnected.isSuspended());
        assertFalse(disconnected.resume("late"));
        // the client went away: the response was not sent
        assertInstanceOf(ConnectionClosedException.class, COMPLETED.poll(5, TimeUnit.SECONDS));
        // the timeout of the suspended response is cancelled
        assertNull(STATES.poll(1, TimeUnit.SECONDS));
        assertNull(DISCONNECTED.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void callbacksAreNotCalledOnTheEventLoop() throws Exception {
        COMPLETED.clear();
        DISCONNECTED.clear();
        assertEquals("completed", get("callback-thread").body());
        String thread = (String) COMPLETED.poll(5, TimeUnit.SECONDS);
        assertFalse(thread.toLowerCase(java.util.Locale.ROOT).contains("eventloop"), thread);
        try (Socket socket = new Socket(server.getHost(), server.getPort())) {
            socket.getOutputStream().write(("GET /api/async-response/disconnect-thread HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertEquals("registered", STATES.poll(5, TimeUnit.SECONDS));
        }
        thread = (String) DISCONNECTED.poll(5, TimeUnit.SECONDS);
        assertFalse(thread.toLowerCase(java.util.Locale.ROOT).contains("eventloop"), thread);
    }

    @Test
    void connectionCallbackIsNotCalledAfterTheResponse() throws Exception {
        COMPLETED.clear();
        DISCONNECTED.clear();
        assertEquals("completed", get("callback").body());
        assertEquals("none", COMPLETED.poll(5, TimeUnit.SECONDS));
        assertNull(DISCONNECTED.poll(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void timeoutHandlerSuspendsIndefinitely() throws Exception {
        HttpResponse<String> response = get("timeout-extended");
        assertEquals(200, response.statusCode());
        assertEquals("extended", response.body());
    }

    @Test
    void resumedValueIsNegotiated() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/async-negotiated"))
            .header("Accept", "application/*").build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("custom:value", response.body());
            assertEquals("application/x-custom", response.headers().firstValue("Content-Type").orElse(null));
        }
    }

    public record CustomEntity(String value) {
    }

    @Requires(property = "spec.name", value = "AsyncResponseTest")
    @Provider
    @Produces("application/x-custom")
    public static class CustomEntityWriter implements MessageBodyWriter<CustomEntity> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == CustomEntity.class;
        }

        @Override
        public void writeTo(CustomEntity entity, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            entityStream.write(("custom:" + entity.value()).getBytes(StandardCharsets.UTF_8));
        }
    }

    public record WrittenEntity(String value) {
    }

    @Requires(property = "spec.name", value = "AsyncResponseTest")
    @Provider
    @Produces("text/x-written")
    public static class WrittenEntityWriter implements MessageBodyWriter<WrittenEntity> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == WrittenEntity.class;
        }

        @Override
        public void writeTo(WrittenEntity entity, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
            if (entity.value().equals("fail")) {
                throw new IOException("broken writer");
            }
            COMPLETED.add("written");
            entityStream.write(("written:" + entity.value()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Requires(property = "spec.name", value = "AsyncResponseTest")
    @Provider
    public static class OrderFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            String path = requestContext.getUriInfo().getPath();
            if (path.endsWith("callback-order") || path.endsWith("callback-written")) {
                COMPLETED.add("filtered");
            }
        }
    }

    @Requires(property = "spec.name", value = "AsyncResponseTest")
    @Path("/async-negotiated")
    public static class NegotiatedResource {

        @GET
        public void negotiated(@Suspended AsyncResponse response) {
            CompletableFuture.runAsync(() -> response.resume(new CustomEntity("value")));
        }
    }

    public static final class MappedException extends RuntimeException {
        public MappedException(String message) {
            super(message);
        }
    }

    @Requires(property = "spec.name", value = "AsyncResponseTest")
    @Provider
    public static class MappedExceptionMapper implements ExceptionMapper<MappedException> {
        @Override
        public Response toResponse(MappedException exception) {
            return Response.status(418).entity("mapped: " + exception.getMessage()).type(MediaType.TEXT_PLAIN).build();
        }
    }

    @Requires(property = "spec.name", value = "AsyncResponseTest")
    @Path("/async-response")
    @Produces(MediaType.TEXT_PLAIN)
    public static class AsyncResource {

        @GET
        @Path("entity")
        public void entity(@Suspended AsyncResponse response) {
            response.resume("resumed");
        }

        @GET
        @Path("response")
        public void response(@Suspended AsyncResponse response) {
            response.resume(Response.status(201).entity("created").header("X-Async", "value").build());
        }

        @GET
        @Path("later")
        public void later(@Suspended AsyncResponse response) {
            CompletableFuture.runAsync(() -> response.resume("later"), CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS));
        }

        @GET
        @Path("mapped")
        public void mapped(@Suspended AsyncResponse response) {
            CompletableFuture.runAsync(() -> response.resume(new MappedException("failed")));
        }

        @GET
        @Path("not-found")
        public void notFound(@Suspended AsyncResponse response) {
            response.resume(new NotFoundException());
        }

        @GET
        @Path("thrown")
        public void thrown(@Suspended AsyncResponse response) {
            throw new MappedException("thrown");
        }

        @GET
        @Path("timeout")
        public void timeout(@Suspended AsyncResponse response) {
            response.setTimeout(100, TimeUnit.MILLISECONDS);
        }

        @GET
        @Path("timeout-handler")
        public void timeoutHandler(@Suspended AsyncResponse response) {
            response.setTimeoutHandler(timedOut -> timedOut.resume("timed out"));
            response.setTimeout(100, TimeUnit.MILLISECONDS);
        }

        @GET
        @Path("cancel")
        public void cancel(@Suspended AsyncResponse response) {
            boolean suspended = response.isSuspended();
            boolean cancelled = response.cancel(5);
            STATES.add(List.of(suspended, cancelled, response.isSuspended(), response.isCancelled() && response.isDone()));
        }

        @GET
        @Path("callback")
        public void callback(@Suspended AsyncResponse response) {
            assertTrue(response.register(CompletionCallbackImpl.class).contains(jakarta.ws.rs.container.CompletionCallback.class));
            // supported by the Netty server
            assertEquals(List.of(ConnectionCallback.class), response.register((ConnectionCallback) DISCONNECTED::add));
            CompletableFuture.runAsync(() -> response.resume("completed"));
        }

        @GET
        @Path("callback-failure")
        public void callbackFailure(@Suspended AsyncResponse response) {
            response.register(new CompletionCallbackImpl());
            assertFalse(response.isDone());
            response.resume(new MappedException("callback"));
            assertFalse(response.resume("too late"));
        }

        @GET
        @Path("callback-unmapped")
        public void callbackUnmapped(@Suspended AsyncResponse response) {
            response.register(new CompletionCallbackImpl());
            response.resume(new IllegalStateException("unmapped"));
        }

        @GET
        @Path("callback-order")
        public void callbackOrder(@Suspended AsyncResponse response) {
            response.register(new CompletionCallbackImpl());
            CompletableFuture.runAsync(() -> response.resume("ordered"));
        }

        @GET
        @Path("callback-written")
        @Produces("text/x-written")
        public void callbackWritten(@Suspended AsyncResponse response) {
            response.register(new CompletionCallbackImpl());
            CompletableFuture.runAsync(() -> response.resume(new WrittenEntity("value")));
        }

        @GET
        @Path("callback-writer-failure")
        @Produces("text/x-written")
        public void callbackWriterFailure(@Suspended AsyncResponse response) {
            response.register(new CompletionCallbackImpl());
            CompletableFuture.runAsync(() -> response.resume(new WrittenEntity("fail")));
        }

        @GET
        @Path("disconnect")
        public void disconnect(@Suspended AsyncResponse response) {
            response.setTimeoutHandler(timedOut -> STATES.add("timed out"));
            response.setTimeout(500, TimeUnit.MILLISECONDS);
            List<Class<?>> registered = new java.util.ArrayList<>(response.register(new CompletionCallbackImpl()));
            registered.addAll(response.register((ConnectionCallback) disconnected -> {
                // the state the callback sees
                STATES.add(List.of(disconnected.isDone(), disconnected.isSuspended(), disconnected.isCancelled(), disconnected.resume("late")));
                DISCONNECTED.add(disconnected);
            }));
            STATES.add(registered);
        }

        @GET
        @Path("callback-thread")
        public void callbackThread(@Suspended AsyncResponse response) {
            response.register((jakarta.ws.rs.container.CompletionCallback) throwable -> COMPLETED.add(Thread.currentThread().getName()));
            CompletableFuture.runAsync(() -> response.resume("completed"));
        }

        @GET
        @Path("disconnect-thread")
        public void disconnectThread(@Suspended AsyncResponse response) {
            response.register((ConnectionCallback) disconnected -> DISCONNECTED.add(Thread.currentThread().getName()));
            STATES.add("registered");
        }

        @GET
        @Path("timeout-extended")
        public void timeoutExtended(@Suspended AsyncResponse response) {
            response.setTimeoutHandler(timedOut -> {
                // no timeout anymore: resumed later, not answered with a 503
                timedOut.setTimeout(AsyncResponse.NO_TIMEOUT, TimeUnit.MILLISECONDS);
                CompletableFuture.runAsync(() -> timedOut.resume("extended"), CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));
            });
            response.setTimeout(100, TimeUnit.MILLISECONDS);
        }
    }

    @io.micronaut.core.annotation.Introspected
    public static class CompletionCallbackImpl implements jakarta.ws.rs.container.CompletionCallback {
        @Override
        public void onComplete(Throwable throwable) {
            COMPLETED.add(throwable == null ? "none" : throwable);
        }
    }
}
