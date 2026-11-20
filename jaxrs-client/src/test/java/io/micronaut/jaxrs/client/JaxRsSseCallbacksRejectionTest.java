package io.micronaut.jaxrs.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * The callbacks of an event source whose executor rejects them do not run on the thread that
 * dispatches them, which may be the event loop that delivers the events the callbacks wait for.
 */
class JaxRsSseCallbacksRejectionTest {

    @Test
    void rejectedCallbacksRunOnAnotherThread() throws Exception {
        JaxRsSseCallbacks callbacks = new JaxRsSseCallbacks(task -> {
            throw new RejectedExecutionException("saturated");
        });
        CompletableFuture<Thread> ran = new CompletableFuture<>();
        callbacks.dispatch(() -> ran.complete(Thread.currentThread()));
        assertNotSame(Thread.currentThread(), ran.get(10, TimeUnit.SECONDS));
    }
}
