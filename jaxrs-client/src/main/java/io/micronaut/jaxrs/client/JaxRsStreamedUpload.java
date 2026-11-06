/*
 * Copyright 2017-2024 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.client;

import io.micronaut.buffer.netty.NettyByteBufferFactory;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.stream.InputStreamByteBody;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.OptionalLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The upload of a request entity larger than the client buffers: the request is sent once the
 * written bytes exceed {@link #THRESHOLD}, with the headers it has then, and the rest of the entity
 * is sent as it is written, the writer waiting while the transport is behind.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsStreamedUpload {

    /**
     * The size of the entities the client writes in memory before sending them.
     */
    static final int THRESHOLD = 1024 * 1024;

    // the pieces written and not sent yet: the writer waits when it is full
    private static final int PENDING_PIECES = 16;
    private static final byte[] END = new byte[0];
    private static final ByteBodyFactory BODY_FACTORY = ByteBodyFactory.createDefault(NettyByteBufferFactory.DEFAULT);

    private final JaxRsClient client;
    private final BlockingQueue<byte[]> pieces = new ArrayBlockingQueue<>(PENDING_PIECES);
    private volatile @Nullable IOException failure;
    private volatile boolean aborted;
    private @Nullable CompletableFuture<HttpResponse<?>> exchange;

    /**
     * @param client The client
     */
    JaxRsStreamedUpload(JaxRsClient client) {
        this.client = client;
    }

    /**
     * @return The exchange, {@code null} if the entity was not larger than {@link #THRESHOLD}
     */
    @Nullable CompletableFuture<HttpResponse<?>> exchange() {
        return exchange;
    }

    /**
     * Send the request, the entity being written: the bytes written so far are sent first.
     *
     * @param request The request, with its headers
     * @param written The bytes written so far
     * @return The stream the rest of the entity is written to
     */
    OutputStream start(MutableHttpRequest<?> request, byte[] written) throws IOException {
        put(written);
        CloseableByteBody body = InputStreamByteBody.create(new PiecesInputStream(), OptionalLong.empty(),
            client.getExecutorService(), BODY_FACTORY);
        exchange = client.httpClient().toAsyncRaw().exchange(request, body).toCompletableFuture();
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (len > 0) {
                    byte[] piece = new byte[len];
                    System.arraycopy(b, off, piece, 0, len);
                    put(piece);
                }
            }
        };
    }

    /**
     * The entity is written: the request ends.
     */
    void finish() throws IOException {
        put(END);
    }

    /**
     * Writing the entity failed: the request is aborted.
     *
     * @param cause The failure
     */
    void fail(Throwable cause) {
        failure = cause instanceof IOException e ? e : new IOException(cause);
        aborted = true;
        pieces.clear();
        // wakes up the transport, which reads the failure
        var _ = pieces.offer(END);
        CompletableFuture<HttpResponse<?>> current = exchange;
        if (current != null) {
            current.cancel(false);
        }
    }

    private void put(byte[] piece) throws IOException {
        if (aborted) {
            throw new IOException("The request was aborted");
        }
        try {
            pieces.put(piece);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    /**
     * The entity as the transport reads it, on the executor of the client.
     */
    private final class PiecesInputStream extends InputStream {
        private byte[] current = new byte[0];
        private int position;
        private boolean ended;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (position == current.length) {
                if (ended) {
                    return -1;
                }
                byte[] next;
                try {
                    next = pieces.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
                IOException failed = failure;
                if (failed != null) {
                    throw failed;
                }
                if (next == END) {
                    ended = true;
                    return -1;
                }
                current = next;
                position = 0;
            }
            int n = Math.min(len, current.length - position);
            System.arraycopy(current, position, b, off, n);
            position += n;
            return n;
        }

        @Override
        public void close() {
            // the transport no longer reads: the writer does not wait for it
            aborted = true;
            pieces.clear();
        }
    }
}
