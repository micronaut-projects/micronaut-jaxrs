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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableByteBody;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * The upload of a request entity larger than the client buffers: the request is sent once the
 * written bytes exceed {@link #THRESHOLD}, with the headers it has then, and the rest of the entity
 * is sent as it is written, the writer waiting while the transport is behind.
 *
 * <p>The body is a streaming body of the body factory: its writer, which runs when the transport
 * reads the body, hands its stream over to the thread writing the entity, and returns once the
 * entity is written.
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

    private static final ByteBodyFactory BODY_FACTORY = ByteBodyFactory.createDefault(ByteArrayBufferFactory.INSTANCE);
    // the writer of the body waits on a thread of its own, not on the executor of the client: the
    // writer of an asynchronous invocation, which waits for the transport, may hold its only thread
    private static final Executor WRITER = runnable -> Thread.ofVirtual().name("jaxrs-client-upload").start(runnable);

    private final JaxRsClient client;
    // the stream of the body, once the transport reads it
    private final CompletableFuture<OutputStream> stream = new CompletableFuture<>();
    // completes when the entity is written, exceptionally when writing it failed
    private final CompletableFuture<Void> written = new CompletableFuture<>();
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
     * @param bytes   The bytes written so far
     * @return The stream the rest of the entity is written to
     */
    OutputStream start(MutableHttpRequest<?> request, byte[] bytes) throws IOException {
        CloseableByteBody body = BODY_FACTORY.streaming(WRITER, out -> {
            out.write(bytes);
            stream.complete(out);
            try {
                written.get();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            }
        });
        CompletableFuture<HttpResponse<?>> current = client.httpClient().toAsyncRaw().exchange(request, body).toCompletableFuture();
        exchange = current;
        // the transport no longer reads, e.g. the request failed: the writer does not wait for it
        var _ = current.whenComplete((response, error) -> stream.completeExceptionally(new IOException("The request was aborted")));
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                stream().write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (len > 0) {
                    stream().write(b, off, len);
                }
            }

            @Override
            public void flush() throws IOException {
                stream().flush();
            }
        };
    }

    /**
     * The entity is written: the request ends.
     */
    void finish() throws IOException {
        // the stream is handed over before the writer waits, which then closes it
        var _ = stream();
        written.complete(null);
    }

    /**
     * Writing the entity failed: the request is aborted.
     *
     * @param cause The failure
     */
    void fail(Throwable cause) {
        written.completeExceptionally(cause);
        CompletableFuture<HttpResponse<?>> current = exchange;
        if (current != null) {
            current.cancel(false);
        }
    }

    private OutputStream stream() throws IOException {
        try {
            return stream.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
    }
}
