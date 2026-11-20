package io.micronaut.jaxrs.common;

import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing a response closes the entity stream a response filter replaced with another one, not a
 * wrapper of it, e.g. the stream of the body as it arrives, which holds the connection.
 */
class ReplacedEntityStreamReleaseTest {

    @Test
    void closeReleasesTheReplacedStream() {
        TrackedStream received = new TrackedStream();
        TrackedStream replacement = new TrackedStream();
        JaxRsMutableResponse response = new JaxRsMutableResponse(HttpResponse.ok());
        response.setEntityStream(received);
        response.setEntityStream(replacement);
        response.close();
        assertTrue(received.closed);
        assertTrue(replacement.closed);
    }

    private static final class TrackedStream extends ByteArrayInputStream {
        volatile boolean closed;

        TrackedStream() {
            super(new byte[] {1, 2, 3});
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
