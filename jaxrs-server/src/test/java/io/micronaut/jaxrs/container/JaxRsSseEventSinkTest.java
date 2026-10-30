package io.micronaut.jaxrs.container;

import io.micronaut.http.sse.Event;
import io.micronaut.jaxrs.common.sse.JaxRsOutboundSseEvent;
import jakarta.ws.rs.sse.OutboundSseEvent;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage of a sent event completes once the response stream takes the event (JAX-RS 9.3).
 */
class JaxRsSseEventSinkTest {

    private static OutboundSseEvent event(String data) {
        return new JaxRsOutboundSseEvent.Builder().data(data).build();
    }

    @Test
    void sendCompletesWhenTheEventIsTaken() {
        var sink = new JaxRsSseEventSink(e -> String.valueOf(e.getData()));
        var subscription = new AtomicReference<Subscription>();
        List<String> received = new CopyOnWriteArrayList<>();
        sink.publisher().subscribe(new TestSubscriber(subscription, received));

        // no demand: the event waits, and so does a producer chaining sends
        CompletableFuture<?> first = sink.send(event("one")).toCompletableFuture();
        CompletableFuture<?> second = sink.send(event("two")).toCompletableFuture();
        assertFalse(first.isDone());
        assertFalse(second.isDone());

        subscription.get().request(1);
        assertTrue(first.isDone());
        assertFalse(second.isDone());
        assertEquals(List.of("one"), received);

        subscription.get().request(1);
        assertTrue(second.isDone());
        assertEquals(List.of("one", "two"), received);
    }

    @Test
    void sendFailsWhenTheClientDisconnects() {
        var sink = new JaxRsSseEventSink(e -> String.valueOf(e.getData()));
        var subscription = new AtomicReference<Subscription>();
        sink.publisher().subscribe(new TestSubscriber(subscription, new CopyOnWriteArrayList<>()));
        CompletableFuture<?> pending = sink.send(event("one")).toCompletableFuture();

        subscription.get().cancel();

        ExecutionException failure = assertThrows(ExecutionException.class, pending::get);
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(sink.isClosed());
        // the javadoc of SseEventSink and the TCK: sending to a closed sink throws
        assertThrows(IllegalStateException.class, () -> sink.send(event("two")));
    }

    @Test
    void eventsBufferedBeforeTheResponseComplete() {
        // the resource method can wait for its stages before the response is written
        var sink = new JaxRsSseEventSink(e -> String.valueOf(e.getData()));
        assertTrue(sink.send(event("one")).toCompletableFuture().isDone());
        for (int i = 1; i < JaxRsSseEventSink.UNSUBSCRIBED_LIMIT; i++) {
            sink.send(event("more"));
        }
        // the buffer before the response is bounded: then the stages follow the demand
        CompletableFuture<?> over = sink.send(event("over")).toCompletableFuture();
        assertFalse(over.isDone());

        var subscription = new AtomicReference<Subscription>();
        sink.publisher().subscribe(new TestSubscriber(subscription, new CopyOnWriteArrayList<>()));
        subscription.get().request(Long.MAX_VALUE);
        assertTrue(over.isDone());
    }

    @Test
    void sendFailsWhenTheSinkIsClosedWhileTheEventIsWritten() {
        var sinkRef = new AtomicReference<JaxRsSseEventSink>();
        // closed by another thread while the data is written
        var sink = new JaxRsSseEventSink(e -> {
            sinkRef.get().close();
            return String.valueOf(e.getData());
        });
        sinkRef.set(sink);
        var subscription = new AtomicReference<Subscription>();
        List<String> received = new CopyOnWriteArrayList<>();
        sink.publisher().subscribe(new TestSubscriber(subscription, received));
        subscription.get().request(Long.MAX_VALUE);

        CompletableFuture<?> sent = sink.send(event("one")).toCompletableFuture();

        // the stage settles, and the event is not published after the completion
        ExecutionException failure = assertThrows(ExecutionException.class, sent::get);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(received.isEmpty());
    }

    @Test
    void lineBreaksDoNotStartAnotherEvent() {
        // a CR, an LF and a CRLF each end a line of the event stream (HTML 9.2.5): the data lines
        // are written by the stream writer on LF, so every line break of the data becomes an LF
        var sink = new JaxRsSseEventSink(e -> String.valueOf(e.getData()));
        var subscription = new AtomicReference<Subscription>();
        List<Event<String>> received = new CopyOnWriteArrayList<>();
        sink.publisher().subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                subscription.set(s);
            }

            @Override
            public void onNext(Event<String> e) {
                received.add(e);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
        subscription.get().request(Long.MAX_VALUE);

        sink.send(new JaxRsOutboundSseEvent.Builder().data("safe\r\rdata: forged\r\nx")
            .comment("note\revent: forged").build());
        assertEquals("safe\n\ndata: forged\nx", received.get(0).getData());
        assertEquals("note\n: event: forged", received.get(0).getComment());

        // the name and the id are one line
        CompletableFuture<?> name = sink.send(new JaxRsOutboundSseEvent.Builder().data("d")
            .name("a\nevent: b").build()).toCompletableFuture();
        CompletableFuture<?> id = sink.send(new JaxRsOutboundSseEvent.Builder().data("d")
            .id("a\rb").build()).toCompletableFuture();
        assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class, name::get).getCause());
        assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class, id::get).getCause());
        assertEquals(1, received.size());
    }

    @Test
    void thePendingEventsAreBounded() {
        var sink = new JaxRsSseEventSink(e -> String.valueOf(e.getData()));
        sink.publisher().subscribe(new TestSubscriber(new AtomicReference<>(), new CopyOnWriteArrayList<>()));
        for (int i = 0; i < JaxRsSseEventSink.PENDING_LIMIT; i++) {
            sink.send(event("e"));
        }
        // a producer ignoring the stages cannot grow the buffer without bound
        CompletableFuture<?> over = sink.send(event("over")).toCompletableFuture();
        assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, over::get).getCause());
    }

    private record TestSubscriber(AtomicReference<Subscription> subscription,
                                  List<String> received) implements Subscriber<Event<String>> {
        @Override
        public void onSubscribe(Subscription s) {
            subscription.set(s);
        }

        @Override
        public void onNext(Event<String> event) {
            received.add(event.getData());
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onComplete() {
        }
    }

    @Test
    void theCloseCallbacksOfADisconnectRunOnTheCallbackExecutor() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "callbacks"));
        try {
            var sink = new JaxRsSseEventSink(e -> String.valueOf(e.getData()), executor);
            CompletableFuture<String> thread = new CompletableFuture<>();
            sink.onClose(() -> thread.complete(Thread.currentThread().getName()));
            // the subscription of the connection is cancelled on the event loop
            sink.cancel();
            assertTrue(sink.isClosed());
            assertEquals("callbacks", thread.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }
    }
}
