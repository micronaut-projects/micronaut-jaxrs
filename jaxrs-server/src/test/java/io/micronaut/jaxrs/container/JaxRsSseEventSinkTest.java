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
}
