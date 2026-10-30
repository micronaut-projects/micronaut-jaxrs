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
    void aBroadcasterClosedWithoutCascadingReleasesItsSinks() {
        var sink = new JaxRsSseEventSink(e -> String.valueOf(e.getData()));
        var broadcaster = new JaxRsSseBroadcaster();
        var notified = new java.util.concurrent.atomic.AtomicInteger();
        broadcaster.onClose(s -> notified.incrementAndGet());
        broadcaster.register(sink);
        broadcaster.close(false);
        // the sink is open: no close is reported
        Assertions.assertEquals(0, notified.get());
        Assertions.assertFalse(sink.isClosed());
        // and the closed broadcaster is not called back by the sink, which no longer holds it
        sink.close();
        Assertions.assertEquals(0, notified.get());
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

    @Test
    void aClosedSinkIsNotRetained() {
        var broadcaster = new JaxRsSseBroadcaster();
        for (int i = 0; i < 100; i++) {
            var sink = new JaxRsSseEventSink(e -> String.valueOf(e.getData()));
            sink.close();
            broadcaster.register(sink);
        }
        Assertions.assertEquals(0, broadcaster.registrations());
    }

    @Test
    void aSinkRegisteredTwiceReceivesAnEventOnce() {
        var broadcaster = new JaxRsSseBroadcaster();
        var sink = new TestSink();
        broadcaster.register(sink);
        broadcaster.register(sink);
        broadcaster.broadcast(new JaxRsSse().newEvent("data"));
        Assertions.assertEquals(1, sink.sent);
    }

    @Test
    void aSinkClosedWhileRegisteredIsNotRetained() throws Exception {
        for (int i = 0; i < 2000; i++) {
            var broadcaster = new JaxRsSseBroadcaster();
            var sink = new JaxRsSseEventSink(e -> String.valueOf(e.getData()));
            var barrier = new CyclicBarrier(2);
            Thread closing = new Thread(() -> {
                try {
                    barrier.await();
                    sink.close();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            closing.start();
            barrier.await();
            broadcaster.register(sink);
            closing.join();
            int iteration = i;
            Assertions.assertEquals(0, broadcaster.registrations(), () -> "a closed sink retained, iteration " + iteration);
        }
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
