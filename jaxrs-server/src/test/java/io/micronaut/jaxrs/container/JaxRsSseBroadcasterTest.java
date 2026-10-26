package io.micronaut.jaxrs.container;

import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing a broadcaster closes every sink registered with it (JAX-RS 9.5).
 */
class JaxRsSseBroadcasterTest {

    @Test
    void aSinkRegisteredConcurrentlyWithCloseIsClosed() throws Exception {
        for (int i = 0; i < 2000; i++) {
            var broadcaster = new JaxRsSseBroadcaster();
            var sink = new TestSink();
            var barrier = new CyclicBarrier(2);
            var registered = new AtomicBoolean();
            Thread registering = new Thread(() -> {
                try {
                    barrier.await();
                    broadcaster.register(sink);
                    registered.set(true);
                } catch (IllegalStateException e) {
                    // registered after the close
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            registering.start();
            barrier.await();
            broadcaster.close();
            registering.join();
            if (registered.get()) {
                int iteration = i;
                assertTrue(sink.isClosed(), () -> "an open sink under a closed broadcaster, iteration " + iteration);
            }
        }
    }

    @Test
    void aSinkFailingToSendDoesNotStopTheBroadcast() {
        var broadcaster = new JaxRsSseBroadcaster();
        List<Throwable> errors = new ArrayList<>();
        broadcaster.onError((sink, error) -> errors.add(error));
        var failing = new TestSink() {
            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                throw new IllegalStateException("closed");
            }
        };
        var other = new TestSink();
        broadcaster.register(failing);
        broadcaster.register(other);
        CompletionStage<?> sent = broadcaster.broadcast(new JaxRsSse().newEvent("data"));
        Assertions.assertEquals(1, other.sent);
        Assertions.assertEquals(1, errors.size());
        assertTrue(sent.toCompletableFuture().isCompletedExceptionally());
    }

    private static class TestSink implements SseEventSink {

        private volatile boolean closed;
        int sent;

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public CompletionStage<?> send(OutboundSseEvent event) {
            sent++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
