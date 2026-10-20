package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.ServerHttpRequest;
import jakarta.ws.rs.container.CompletionCallback;
import jakarta.ws.rs.container.ConnectionCallback;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The states of a {@link JaxRsAsyncResponse}.
 */
class JaxRsAsyncResponseTest {

    private static JaxRsAsyncResponse asyncResponse() {
        return new JaxRsAsyncResponse(value -> value instanceof Response response
            ? HttpResponse.status(response.getStatus(), null) : HttpResponse.ok(value), () -> null, () -> null);
    }

    @Test
    void resumeAndCancelAreExclusive() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2000; i++) {
                JaxRsAsyncResponse asyncResponse = asyncResponse();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Boolean> resumed = executor.submit(() -> {
                    barrier.await();
                    return asyncResponse.resume("ok");
                });
                Future<Boolean> cancelled = executor.submit(() -> {
                    barrier.await();
                    return asyncResponse.cancel();
                });
                boolean resume = resumed.get(5, TimeUnit.SECONDS);
                boolean cancel = cancelled.get(5, TimeUnit.SECONDS);
                assertNotEquals(resume, cancel);
                assertEquals(cancel, asyncResponse.isCancelled());
                assertTrue(asyncResponse.isDone());
                int status = asyncResponse.response().toCompletableFuture().get(5, TimeUnit.SECONDS).code();
                assertEquals(cancel ? 503 : 200, status);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelAfterCancelAndResume() {
        JaxRsAsyncResponse asyncResponse = asyncResponse();
        assertTrue(asyncResponse.cancel());
        assertTrue(asyncResponse.cancel());
        assertFalse(asyncResponse.resume("late"));
        JaxRsAsyncResponse resumed = asyncResponse();
        assertTrue(resumed.resume("ok"));
        assertFalse(resumed.cancel());
        assertFalse(resumed.isCancelled());
    }

    @Test
    void callbacksWithoutTheSupportOfTheServer() {
        // e.g. a servlet container: called once the response was processed, no connection callback
        MutableHttpRequest<?> request = HttpRequest.GET("/");
        JaxRsAsyncResponse asyncResponse = asyncResponse();
        request.setAttribute(JaxRsRuntimeRoutes.ASYNC_RESPONSE, asyncResponse);
        asyncResponse.bind(request);
        List<Object> completed = new ArrayList<>();
        assertEquals(List.of(CompletionCallback.class), asyncResponse.register((CompletionCallback) t -> completed.add(t == null ? "none" : t)));
        assertTrue(asyncResponse.register((ConnectionCallback) r -> completed.add("disconnected")).isEmpty());
        assertTrue(asyncResponse.register(ConnectionCallback.class).isEmpty());
        JaxRsAsyncResponse.completed(request);
        // still suspended
        assertTrue(completed.isEmpty());
        assertTrue(asyncResponse.resume("ok"));
        JaxRsAsyncResponse.completed(request);
        JaxRsAsyncResponse.completed(request);
        assertEquals(List.of("none"), completed);
    }

    @Test
    void callbacksOfTheServer() {
        List<Consumer<Throwable>> completions = new ArrayList<>();
        List<Runnable> abandonments = new ArrayList<>();
        ServerHttpRequest<?> request = serverRequest(completions, abandonments);
        JaxRsAsyncResponse asyncResponse = asyncResponse();
        asyncResponse.bind(request);
        List<Object> called = new ArrayList<>();
        asyncResponse.register((CompletionCallback) t -> called.add(t == null ? "none" : t));
        assertEquals(List.of(ConnectionCallback.class), asyncResponse.register((ConnectionCallback) r -> called.add("disconnected")));
        assertTrue(asyncResponse.resume("ok"));
        // processed, but not written yet: the server reports it
        JaxRsAsyncResponse.completed(request);
        assertTrue(called.isEmpty());
        IllegalStateException failure = new IllegalStateException("write failed");
        completions.forEach(c -> c.accept(failure));
        assertEquals(List.of(failure), called);
        // a connection callback registered after the client went away is called directly
        JaxRsAsyncResponse abandoned = asyncResponse();
        abandoned.bind(serverRequest(new ArrayList<>(), abandonments));
        abandonments.get(1).run();
        List<Object> disconnected = new ArrayList<>();
        abandoned.register((ConnectionCallback) disconnected::add);
        assertEquals(List.of(abandoned), disconnected);
    }

    private static ServerHttpRequest<?> serverRequest(List<Consumer<Throwable>> completions, List<Runnable> abandonments) {
        MutableHttpRequest<?> attributes = HttpRequest.GET("/");
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "whenResponseCompleted" -> {
                completions.add((Consumer<Throwable>) args[0]);
                yield true;
            }
            case "whenAbandoned" -> {
                abandonments.add((Runnable) args[0]);
                yield (Runnable) () -> { };
            }
            default -> method.invoke(attributes, args);
        };
        return (ServerHttpRequest<?>) Proxy.newProxyInstance(JaxRsAsyncResponseTest.class.getClassLoader(),
            new Class<?>[] {ServerHttpRequest.class}, handler);
    }
}
