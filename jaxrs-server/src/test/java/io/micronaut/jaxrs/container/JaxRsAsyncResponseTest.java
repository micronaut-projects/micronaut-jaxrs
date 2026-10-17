package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
            ? HttpResponse.status(response.getStatus(), null) : HttpResponse.ok(value), () -> null);
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
}
