package io.micronaut.jaxrs.common

/**
 * A stream of bytes that tracks whether it is closed.
 */
class TrackingInputStream extends ByteArrayInputStream {

    boolean closed

    TrackingInputStream(byte[] bytes) {
        super(bytes)
    }

    @Override
    void close() throws IOException {
        closed = true
    }
}
