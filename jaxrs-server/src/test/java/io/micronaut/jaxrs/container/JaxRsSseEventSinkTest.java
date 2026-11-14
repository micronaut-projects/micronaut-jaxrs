package io.micronaut.jaxrs.container;

import io.micronaut.http.sse.Event;
import io.micronaut.jaxrs.common.sse.JaxRsOutboundSseEvent;
import jakarta.ws.rs.sse.OutboundSseEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sink sends the events of a resource method to the emitter of its event stream (JAX-RS 9.3):
 * the stage of a sent event follows the emitter, which completes it once the connection takes it.
 */
class JaxRsSseEventSinkTest {

    private static OutboundSseEvent event(String data) {
        return new JaxRsOutboundSseEvent.Builder().data(data).build();
    }

    @Test
    void theStreamStaysOpenUntilTheSinkIsClosed() throws IOException {
        var events = new TestSseEmitter();
        var sink = new JaxRsSseEventSink(events, e -> String.valueOf(e.getData()));
        // the resource method may send its events after it returns
        assertTrue(events.keptOpen);
        assertFalse(sink.isClosed());

        sink.close();
        assertTrue(events.completed);
        assertTrue(sink.isClosed());
    }

    @Test
    void sendCompletesWhenTheEventIsTaken() {
        var events = new TestSseEmitter(false);
        var sink = new JaxRsSseEventSink(events, e -> String.valueOf(e.getData()));

        // the connection did not take the events: a producer chaining sends waits
        CompletableFuture<?> first = sink.send(event("one")).toCompletableFuture();
        CompletableFuture<?> second = sink.send(event("two")).toCompletableFuture();
        assertFalse(first.isDone());
        assertFalse(second.isDone());

        events.take();
        assertTrue(first.isDone());
        assertFalse(second.isDone());

        events.take();
        assertTrue(second.isDone());
        assertEquals(List.of("one", "two"), events.sent.stream().map(Event::getData).toList());
    }

    @Test
    void sendFailsWhenTheClientDisconnects() {
        var events = new TestSseEmitter(false);
        var sink = new JaxRsSseEventSink(events, e -> String.valueOf(e.getData()));
        CompletableFuture<?> pending = sink.send(event("one")).toCompletableFuture();

        events.disconnect();

        ExecutionException failure = assertThrows(ExecutionException.class, pending::get);
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(sink.isClosed());
        // the javadoc of SseEventSink and the TCK: sending to a closed sink throws
        assertThrows(IllegalStateException.class, () -> sink.send(event("two")));
    }

    @Test
    void aDisconnectNoticedByTheRequestFailsTheStream() {
        var events = new TestSseEmitter(false);
        var sink = new JaxRsSseEventSink(events, e -> String.valueOf(e.getData()));
        CompletableFuture<?> pending = sink.send(event("one")).toCompletableFuture();

        // the request was abandoned before the response was sent
        sink.disconnected();

        assertTrue(sink.isClosed());
        assertTrue(pending.isCompletedExceptionally());
        assertInstanceOf(IOException.class, events.failure);
    }

    @Test
    void theCloseCallbacksRunOnce() throws IOException {
        var events = new TestSseEmitter();
        var sink = new JaxRsSseEventSink(events, e -> String.valueOf(e.getData()));
        var calls = new AtomicInteger();
        sink.onClose(calls::incrementAndGet);

        // closed by the application: the callbacks run on its thread
        sink.close();
        assertEquals(1, calls.get());
        sink.close();
        events.disconnect();
        assertEquals(1, calls.get());

        // added once closed: called at once
        sink.onClose(calls::incrementAndGet);
        assertEquals(2, calls.get());
    }

    @Test
    void theCloseCallbacksOfADisconnectRunOnTheCallbackExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "callbacks"));
        try {
            var events = new TestSseEmitter();
            var sink = new JaxRsSseEventSink(events, e -> String.valueOf(e.getData()), executor);
            CompletableFuture<String> thread = new CompletableFuture<>();
            sink.onClose(() -> thread.complete(Thread.currentThread().getName()));
            // the disconnect is reported on the event loop
            events.disconnect();
            assertTrue(sink.isClosed());
            assertEquals("callbacks", thread.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void aFailingDataWriterFailsTheSend() {
        var events = new TestSseEmitter();
        var sink = new JaxRsSseEventSink(events, e -> {
            throw new IllegalStateException("no writer");
        });
        CompletableFuture<?> sent = sink.send(event("one")).toCompletableFuture();
        assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, sent::get).getCause());
        // the stream stays open
        assertFalse(sink.isClosed());
        assertTrue(events.sent.isEmpty());
    }

    @Test
    void theFieldsOfTheEventAreSent() {
        var events = new TestSseEmitter();
        var sink = new JaxRsSseEventSink(events, e -> String.valueOf(e.getData()));
        sink.send(new JaxRsOutboundSseEvent.Builder().data("d").id("1").name("n").comment("c").reconnectDelay(500).build());
        sink.send(new JaxRsOutboundSseEvent.Builder().comment("only").build());

        Event<?> first = events.sent.get(0);
        assertEquals("d", first.getData());
        assertEquals("1", first.getId());
        assertEquals("n", first.getName());
        assertEquals("c", first.getComment());
        assertEquals(500, first.getRetry().toMillis());
        // an event without data: the data writer is not asked
        assertEquals("", events.sent.get(1).getData());
    }

    @Test
    void lineBreaksDoNotStartAnotherEvent() {
        var events = new TestSseEmitter();
        var sink = new JaxRsSseEventSink(events, e -> String.valueOf(e.getData()));

        sink.send(new JaxRsOutboundSseEvent.Builder().data("safe\r\rdata: forged\r\nx")
            .comment("note\revent: forged").build());
        // the stream writer writes every line of the data and the comment as a field
        assertEquals("safe\r\rdata: forged\r\nx", events.sent.get(0).getData());
        assertEquals("note\revent: forged", events.sent.get(0).getComment());

        // the name and the id are one line
        CompletableFuture<?> name = sink.send(new JaxRsOutboundSseEvent.Builder().data("d")
            .name("a\nevent: b").build()).toCompletableFuture();
        CompletableFuture<?> id = sink.send(new JaxRsOutboundSseEvent.Builder().data("d")
            .id("a\rb").build()).toCompletableFuture();
        assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class, name::get).getCause());
        assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class, id::get).getCause());
        assertEquals(1, events.sent.size());
    }

    @Test
    void sendFailsWhenTheSinkIsClosedWhileTheEventIsWritten() {
        var events = new TestSseEmitter();
        var sinkRef = new AtomicReference<JaxRsSseEventSink>();
        // closed by another thread while the data is written
        var sink = new JaxRsSseEventSink(events, e -> {
            sinkRef.get().close();
            return String.valueOf(e.getData());
        });
        sinkRef.set(sink);

        CompletableFuture<?> sent = sink.send(event("one")).toCompletableFuture();

        // the stage settles, and the event is not sent after the completion
        assertThrows(ExecutionException.class, sent::get);
        assertTrue(events.sent.isEmpty());
    }
}
