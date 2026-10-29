/*
 * Copyright 2017-2026 original authors
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

import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.ByteBodyHttpResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.NoContentException;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Set;

/**
 * The entity of a {@link JaxRsResponse} and its lifecycle: its buffering, its reads and their
 * caches, its consumption and the closing of the response. The entity of a
 * {@link JaxRsMutableResponse} can also be a stream, e.g. the one a filter sets, and an entity that
 * holds a resource is released when the response is closed.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
final class JaxRsResponseEntity {

    // the standard types (JAX-RS 4.2.4) without a zero-length value, which read an empty entity as null
    private static final Set<String> STANDARD_NULLABLE_TYPES = Set.of(
        "java.io.File",
        "jakarta.activation.DataSource",
        "javax.xml.transform.Source",
        "javax.xml.transform.stream.StreamSource",
        "javax.xml.transform.sax.SAXSource",
        "javax.xml.transform.dom.DOMSource",
        "jakarta.xml.bind.JAXBElement",
        "jakarta.ws.rs.core.MultivaluedMap",
        "jakarta.ws.rs.core.StreamingOutput"
    );

    private final HttpResponse<?> response;
    // the same response, when it is mutable: its entity can be a stream
    private final @Nullable MutableHttpResponse<?> mutableResponse;
    private final HttpMessageEntityReader entityReader;
    private boolean buffered;
    // close() was called
    private boolean closed;
    // the entity stream was read without being buffered
    private boolean consumed;
    // the entity was read without being buffered as the entity stream, which stays open
    private boolean streamRead;
    private byte @Nullable [] buffer;
    @Nullable
    private Argument<?> readBodyArgument;
    @Nullable
    private Object readBody;
    // readEntity returned an entity of the buffered entity, other than the stream, which getEntity returns
    private boolean entityRead;
    @Nullable
    private Object readEntity;
    // the entity stream of a mutable response, e.g. the one a filter set, not read yet
    @Nullable
    private InputStream entityStream;

    /**
     * @param response        The response
     * @param mutableResponse The same response, when it is mutable
     * @param entityReader    The reader of the entity
     */
    JaxRsResponseEntity(HttpResponse<?> response, @Nullable MutableHttpResponse<?> mutableResponse, HttpMessageEntityReader entityReader) {
        this.response = response;
        this.mutableResponse = mutableResponse;
        this.entityReader = entityReader;
    }

    /**
     * @return The entity, see {@link jakarta.ws.rs.core.Response#getEntity()}
     */
    @Nullable Object getEntity() {
        if (entityStream != null) {
            checkCanReadEntity();
            // the entity stream, e.g. the one a filter set, not read yet
            return entityStream;
        }
        checkNotClosed();
        if (entityRead) {
            // the last entity read is cached (JAX-RS, Response#readEntity)
            return readEntity;
        }
        checkCanReadEntity();
        Object body = body();
        if (body instanceof GenericEntity<?> genericEntity) {
            return genericEntity.getEntity();
        }
        if (body instanceof ByteBuffer<?> byteBuffer) {
            // the entity of a received response, not read yet, is its input stream
            return mutableResponse != null ? getEntityStream() : new ByteArrayInputStream(byteBuffer.toByteArray());
        }
        return body;
    }

    /**
     * @param entityType The entity argument
     * @param <T>        The entity type
     * @return The entity, see {@link jakarta.ws.rs.core.Response#readEntity(Class)}
     */
    @SuppressWarnings("unchecked")
    <T> @Nullable T readEntity(Argument<T> entityType) {
        checkNotClosed();
        if (readBodyArgument != null && sameEntityType(readBodyArgument, entityType)) {
            return (T) readBody;
        }
        boolean empty = isEmptyEntity();
        T value;
        if (buffered) {
            // the buffer is read again by each read
            value = entityReader.readEntity(response.toMutableResponse().body(buffer), entityType);
        } else {
            if (consumed) {
                throw new IllegalStateException("The entity was read already, without being buffered");
            }
            consumed = true;
            boolean stream = entityType.getType() == InputStream.class;
            streamRead = stream;
            try {
                value = (T) readUnbuffered(entityType);
            } finally {
                if (!stream) {
                    // the entity stream is closed, unless it is the one requested (JAX-RS, Response#readEntity)
                    release();
                }
            }
            if (value == null && empty) {
                // the value of the empty entity is cached, not null, or nothing when it cannot be read
                value = emptyEntity(entityType.getType());
            }
            readBodyArgument = entityType;
            readBody = value;
            return value;
        }
        if (value == null && empty) {
            value = emptyEntity(entityType.getType());
        }
        cacheEntity(entityType, value);
        return value;
    }

    /**
     * @return Whether there is an entity, see {@link jakarta.ws.rs.core.Response#hasEntity()}
     */
    boolean hasEntity() {
        checkCanQueryEntity();
        return entityStream != null || body() != null;
    }

    /**
     * @return Whether the response is closed, see {@link jakarta.ws.rs.core.Response#isClosed()}
     */
    boolean isClosed() {
        // the entity stream that was read stays open (JAX-RS, Response#readEntity)
        return closed || consumed && !buffered && !streamRead;
    }

    /**
     * @return Whether the entity is buffered, see {@link jakarta.ws.rs.core.Response#bufferEntity()}
     */
    boolean bufferEntity() {
        checkNotClosed();
        if (!buffered) {
            checkCanReadEntity();
            byte @Nullable [] body = bufferedBytes();
            if (body == null) {
                return false;
            }
            buffer = body;
            buffered = true;
        }
        return buffered;
    }

    /**
     * Close the response, see {@link jakarta.ws.rs.core.Response#close()}.
     */
    void close() {
        if (!closed) {
            closed = true;
            release();
        }
    }

    /**
     * @return The entity stream of a mutable response, of its bytes when none was set
     */
    InputStream getEntityStream() {
        if (entityStream == null) {
            byte[] bytes = mutableResponse().getBody(byte[].class).orElse(new byte[] {});
            entityStream = new ByteArrayInputStream(bytes);
        }
        return entityStream;
    }

    /**
     * @param entityStream The entity stream of a mutable response
     */
    void setEntityStream(InputStream entityStream) {
        this.entityStream = entityStream;
    }

    private MutableHttpResponse<?> mutableResponse() {
        if (mutableResponse == null) {
            throw new IllegalStateException("The response is not mutable");
        }
        return mutableResponse;
    }

    /**
     * Whether an entity read before is the one requested: the same type, with the same type
     * arguments and annotations.
     */
    private static boolean sameEntityType(Argument<?> read, Argument<?> requested) {
        return read.equalsType(requested)
            && read.getAnnotationMetadata().getAnnotationNames().equals(requested.getAnnotationMetadata().getAnnotationNames());
    }

    /**
     * Cache an entity read from the buffered entity for {@link #getEntity()}: not a stream. An
     * entity read without being buffered is consumed, getEntity throws, as the TCK expects.
     */
    private void cacheEntity(Argument<?> entityType, @Nullable Object value) {
        if (entityType.getType() != InputStream.class) {
            entityRead = true;
            readEntity = value;
        }
    }

    /**
     * Whether the entity is empty: none, or zero-length.
     */
    private boolean isEmptyEntity() {
        if (buffered) {
            return buffer == null || buffer.length == 0;
        }
        Object body = body();
        return body == null
            || body instanceof ByteBuffer<?> byteBuffer && byteBuffer.readableBytes() == 0
            || body instanceof byte[] bytes && bytes.length == 0;
    }

    /**
     * Read the entity, which is not buffered: the entity stream of a mutable response is read,
     * unless it is the one requested.
     */
    private @Nullable Object readUnbuffered(Argument<?> entityType) {
        InputStream stream = entityStream;
        if (stream != null) {
            if (entityType.getType() == InputStream.class) {
                // it stays the entity stream, which close() closes
                return stream;
            }
            entityStream = null;
            mutableResponse().body(readAll(stream));
        }
        return entityReader.readEntity(response, entityType);
    }

    /**
     * The value of an empty entity of a type (JAX-RS 4.2.4): a zero-length one of the standard
     * types that have one.
     */
    @SuppressWarnings("unchecked")
    private static <T> @Nullable T emptyEntity(Class<T> type) {
        if (isPrimitiveOrWrapper(type)) {
            // the standard readers of these types cannot read an empty entity
            throw new ProcessingException(new NoContentException("An empty entity cannot be read as " + type.getName()));
        }
        if (type == String.class) {
            return (T) "";
        }
        if (type == byte[].class) {
            return (T) new byte[0];
        }
        if (type == InputStream.class) {
            return (T) InputStream.nullInputStream();
        }
        if (type == Reader.class) {
            return (T) Reader.nullReader();
        }
        if (type == Object.class || STANDARD_NULLABLE_TYPES.contains(type.getName())) {
            // the other standard types read an empty entity as null, as the TCK expects
            return null;
        }
        // an application type has no zero-length representation (JAX-RS, Response#readEntity)
        throw new ProcessingException(new NoContentException("An empty entity cannot be read as " + type.getName()));
    }

    private static boolean isPrimitiveOrWrapper(Class<?> type) {
        return type.isPrimitive() || type == Boolean.class || type == Character.class || Number.class.isAssignableFrom(type);
    }

    /**
     * Check that the entity can be queried: like {@link #checkCanReadEntity()}, but the entity
     * stream that was read is still the entity of the response, which is not closed.
     */
    private void checkCanQueryEntity() {
        if (!streamRead || buffered) {
            checkCanReadEntity();
        } else {
            checkNotClosed();
        }
    }

    /**
     * The body of the response: its object, else its received bytes, e.g. the ones of an error
     * response the HTTP client did not convert.
     */
    private @Nullable Object body() {
        Object body = response.body();
        if (body == null && response instanceof ByteBodyHttpResponse<?> byteBodyResponse && byteBodyResponse.hasByteBody()) {
            return response.getBody(ByteBuffer.class).orElse(null);
        }
        return body;
    }

    /**
     * Check that the entity can be read: the response is not closed, and its entity is buffered
     * or not read yet.
     */
    private void checkCanReadEntity() {
        checkNotClosed();
        if (consumed && !buffered) {
            throw new IllegalStateException("The entity was read already, without being buffered");
        }
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Response closed");
        }
    }

    /**
     * The bytes of the entity that is buffered: of the entity stream of a mutable response, e.g.
     * the one a response filter set, or of an entity that is a stream.
     *
     * @return The bytes, {@code null} if there is no entity
     */
    private byte @Nullable [] bufferedBytes() {
        if (mutableResponse != null) {
            InputStream stream = entityStream;
            if (stream != null) {
                entityStream = null;
                byte[] bytes = readAll(stream);
                mutableResponse.body(bytes);
                return bytes;
            }
            if (mutableResponse.body() instanceof InputStream body) {
                // an entity that is a stream is buffered as its bytes
                byte[] bytes = readAll(body);
                mutableResponse.body(bytes);
                return bytes;
            }
        }
        return response.getBody(byte[].class).orElse(null);
    }

    /**
     * Read a stream to its end, and close it.
     */
    private static byte[] readAll(InputStream stream) {
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
    }

    /**
     * Release the resources of the entity: the entity of a received response is in memory, the
     * entity stream of a mutable response, and an entity that holds a resource, are closed.
     */
    private void release() {
        if (mutableResponse == null) {
            return;
        }
        try {
            InputStream stream = entityStream;
            if (stream != null) {
                entityStream = null;
                stream.close();
            }
            // an entity that holds a resource, e.g. Response.ok(new FileInputStream(file))
            Object body = mutableResponse.body();
            if (body instanceof GenericEntity<?> genericEntity) {
                body = genericEntity.getEntity();
            }
            if (body instanceof Closeable closeable) {
                closeable.close();
            }
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
    }
}
