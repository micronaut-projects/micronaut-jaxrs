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
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.http.body.BodyElements;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/**
 * The bytes of a received body as they arrive, as an entity stream: a read waits for the next
 * piece of the body, and closing the stream releases the body, e.g. its connection.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsBodyInputStream extends InputStream {

    private final BodyElements<ByteBuffer<?>> body;
    private byte[] pending;
    private int position;
    private boolean ended;

    /**
     * @param first The first piece of the body, received already, empty if none
     * @param body  The other pieces of the body: heap buffers that are not reference counted
     */
    JaxRsBodyInputStream(byte[] first, BodyElements<ByteBuffer<?>> body) {
        this.pending = first;
        this.body = body;
    }

    /**
     * @param body The pieces of the body: heap buffers that are not reference counted
     */
    JaxRsBodyInputStream(BodyElements<ByteBuffer<?>> body) {
        this(new byte[0], body);
    }

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
        while (position == pending.length) {
            if (ended) {
                return -1;
            }
            Optional<ByteBuffer<?>> next;
            try {
                // read on the thread of the reader, e.g. the executor of the client
                next = body.next().toCompletableFuture().join();
            } catch (CompletionException | CancellationException e) {
                throw new IOException(e.getCause() == null ? e : e.getCause());
            }
            if (next.isEmpty()) {
                ended = true;
                return -1;
            }
            pending = next.get().toByteArray();
            position = 0;
        }
        int n = Math.min(len, pending.length - position);
        System.arraycopy(pending, position, b, off, n);
        position += n;
        return n;
    }

    @Override
    public int available() {
        return pending.length - position;
    }

    @Override
    public void close() {
        ended = true;
        pending = new byte[0];
        position = 0;
        var _ = body.closeAsync();
    }
}
