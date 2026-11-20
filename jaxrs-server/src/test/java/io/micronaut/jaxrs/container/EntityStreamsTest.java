package io.micronaut.jaxrs.container;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entity streams the request filters set are closed once the request is processed, also a
 * stream set after they were closed, e.g. when the client disconnected while a filter ran.
 */
class EntityStreamsTest {

    @Test
    void closesTheStreams() {
        JaxRsContainerRequestContext.EntityStreams streams = new JaxRsContainerRequestContext.EntityStreams();
        TrackedStream stream = new TrackedStream();
        streams.add(stream);
        assertFalse(stream.closed);
        streams.close();
        assertTrue(stream.closed);
    }

    @Test
    void closesAStreamAddedOnceClosed() {
        JaxRsContainerRequestContext.EntityStreams streams = new JaxRsContainerRequestContext.EntityStreams();
        streams.close();
        TrackedStream stream = new TrackedStream();
        streams.add(stream);
        assertTrue(stream.closed);
    }

    private static final class TrackedStream extends ByteArrayInputStream {
        volatile boolean closed;

        TrackedStream() {
            super(new byte[] {1});
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
