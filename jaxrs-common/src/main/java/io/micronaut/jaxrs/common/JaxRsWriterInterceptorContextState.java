/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.MutableHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.WriterInterceptorContext;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * State carried through a chain of {@link jakarta.ws.rs.ext.WriterInterceptor} calls. This allows
 * for lazily populating expensive-to-construct fields.
 *
 * @since 4.9.2
 * @author Jonas Konrad
 */
@Internal
public sealed interface JaxRsWriterInterceptorContextState permits JaxRsGenericEntityMessageBodyWriter.ByteBodyState, JaxRsWriterInterceptorContextState.ClassicState {
    /**
     * @see WriterInterceptorContext#getEntity()
     * @return The response entity
     */
    Object getEntity();

    /**
     * @see WriterInterceptorContext#setEntity(Object)
     * @param entity The response entity
     */
    void setEntity(Object entity);

    /**
     * @see WriterInterceptorContext#getOutputStream()
     * @return The response stream
     */
    OutputStream getOutputStream();

    /**
     * @see WriterInterceptorContext#setOutputStream(OutputStream)
     * @param outputStream The response stream
     */
    void setOutputStream(OutputStream outputStream);

    /**
     * @see WriterInterceptorContext#getHeaders()
     * @return The response headers
     */
    MultivaluedMap<String, Object> getHeaders();

    /**
     * Called after the interceptor chain: closes the stream the interceptors installed, e.g. to
     * finish a {@code GZIPOutputStream}, without closing the underlying response stream.
     *
     * @throws IOException If closing fails
     */
    void finish() throws IOException;

    /**
     * A stream that only flushes the given stream when it is closed.
     *
     * @param outputStream The stream
     * @return The stream not to close
     */
    static OutputStream nonClosing(OutputStream outputStream) {
        return new FilterOutputStream(outputStream) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                out.flush();
            }
        };
    }

    /**
     * Implementation with simple fields.
     */
    final class ClassicState implements JaxRsWriterInterceptorContextState {
        final MultivaluedMap<String, Object> headers;
        Object entity;
        OutputStream outputStream;

        public ClassicState(MutableHeaders headers, Object entity, OutputStream outputStream) {
            this.headers = new JaxRsMutableObjectHeadersMultivaluedMap(headers);
            this.entity = entity;
            // the interceptors' streams are closed after the chain, the underlying one is not
            this.outputStream = nonClosing(outputStream);
        }

        @Override
        public void finish() throws IOException {
            outputStream.close();
        }

        @Override
        public Object getEntity() {
            return entity;
        }

        @Override
        public void setEntity(Object entity) {
            this.entity = entity;
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public void setOutputStream(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public MultivaluedMap<String, Object> getHeaders() {
            return headers;
        }
    }
}
