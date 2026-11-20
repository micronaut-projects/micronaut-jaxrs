package io.micronaut.jaxrs.client;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response of a cancelled asynchronous invocation is released also when it was received
 * before its response filters ran: its entity stream, still an attribute of the response, is
 * closed, which releases the connection.
 */
class JaxRsClientExchangeDiscardTest {

    @Test
    void discardClosesTheReceivedEntityStream() {
        TrackedStream stream = new TrackedStream();
        MutableHttpResponse<Object> head = HttpResponse.ok();
        head.setAttribute(JaxRsClientExchange.RECEIVED_ENTITY_STREAM, stream);
        new JaxRsClientExchange.Exchanged(new JaxRsMutableResponse(head), null).discard();
        assertTrue(stream.closed);
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
