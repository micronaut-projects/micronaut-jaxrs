package io.micronaut.jaxrs.client;

import jakarta.ws.rs.sse.SseEventSource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Records the data of the events and the errors of an event source, and waits for a number of events.
 */
final class SseRecorder {

    final List<String> events = new CopyOnWriteArrayList<>();
    final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final CountDownLatch received;

    /**
     * @param expected The number of events {@link #await()} waits for
     */
    SseRecorder(int expected) {
        received = new CountDownLatch(expected);
    }

    /**
     * Register the recorder as a consumer of the events and the errors of the source.
     *
     * @param source The source
     */
    void register(SseEventSource source) {
        source.register(event -> {
            events.add(event.readData());
            received.countDown();
        }, errors::add);
    }

    /**
     * Wait up to ten seconds for the expected events, failing with the events and errors so far.
     *
     * @throws InterruptedException if interrupted
     */
    void await() throws InterruptedException {
        assertTrue(received.await(10, TimeUnit.SECONDS), () -> "events " + events + ", errors " + errors);
    }
}
