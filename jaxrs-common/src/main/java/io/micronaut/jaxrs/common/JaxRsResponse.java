/*
 * Copyright 2017-2020 original authors
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
import io.micronaut.core.io.buffer.ByteBuffer;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.ByteBodyHttpResponse;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseProvider;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.NoContentException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static jakarta.ws.rs.ext.RuntimeDelegate.getInstance;

/**
 * Adapter for JAX-RS and final Micronaut response.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public sealed class JaxRsResponse extends Response implements HttpResponseProvider permits JaxRsMutableResponse {

    /**
     * The attribute of a response with the URI of the request that produced it, which its relative
     * links are resolved against.
     */
    public static final String REQUEST_URI = JaxRsResponse.class.getName() + ".requestUri";

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
    private final HttpMessageEntityReader entityReader;
    private final JaxRsHttpHeaders jaxRsHttpHeaders;
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

    public JaxRsResponse(HttpResponse<?> response) {
        this(response, HttpMessageEntityReader.DEFAULT);
    }

    public JaxRsResponse(HttpResponse<?> response,
                         HttpMessageEntityReader entityReader) {
        this.response = response;
        this.entityReader = entityReader;
        this.jaxRsHttpHeaders = JaxRsHttpHeaders.forResponse(response.getHeaders());
    }

    /**
     * Add the entity reader to the response.
     *
     * @param entityReader The entity reader
     * @return A new response
     */
    public JaxRsResponse withEntityReader(HttpMessageEntityReader entityReader) {
        return new JaxRsResponse(response, entityReader);
    }

    /**
     * @return The Micronaut response object
     */
    @Override
    public HttpResponse<?> getResponse() {
        return response;
    }

    @Override
    public int getStatus() {
        return response.code();
    }

    @Override
    public StatusType getStatusInfo() {
        return new StatusType() {
            @Override
            public int getStatusCode() {
                return getStatus();
            }

            @Override
            public Status.Family getFamily() {
                return Status.Family.familyOf(getStatusCode());
            }

            @Override
            public String getReasonPhrase() {
                String reason = response.reason();
                if (response.code() == 200 && reason.equals("Ok")) {
                    // TCK uses different case
                    reason = "OK";
                }
                return reason;
            }
        };
    }

    @Override
    public @Nullable Object getEntity() {
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
            return receivedEntityStream(byteBuffer);
        }
        return body;
    }

    /**
     * The entity stream of a received response, which {@link #getEntity()} returns.
     *
     * @param byteBuffer The bytes of the entity
     * @return The stream
     */
    InputStream receivedEntityStream(ByteBuffer<?> byteBuffer) {
        return new ByteArrayInputStream(byteBuffer.toByteArray());
    }

    /**
     * Read the entity.
     *
     * @param entityType The entity argument
     * @param <T>        The entity type
     * @return The entity value
     */
    public <T> @Nullable T readEntity(Argument<T> entityType) {
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
     * Read the entity, which is not buffered.
     *
     * @param entityType The type
     * @return The entity
     */
    @Nullable Object readUnbuffered(Argument<?> entityType) {
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

    @Override
    public <T> @Nullable T readEntity(Class<T> entityType) {
        return readEntity(Argument.of(entityType));
    }

    @Override
    public <T> @Nullable T readEntity(GenericType<T> entityType) {
        return readEntity(JaxRsArgumentUtil.from(entityType));
    }

    @Override
    public <T> @Nullable T readEntity(Class<T> entityType, Annotation[] annotations) {
        return readEntity(JaxRsArgumentUtil.from(entityType, annotations));
    }

    @Override
    public <T> @Nullable T readEntity(GenericType<T> entityType, Annotation[] annotations) {
        return readEntity(JaxRsArgumentUtil.from(entityType, annotations));
    }

    @Override
    public boolean hasEntity() {
        checkCanQueryEntity();
        return body() != null;
    }

    @Override
    public boolean isClosed() {
        // the entity stream that was read stays open (JAX-RS, Response#readEntity)
        return closed || consumed && !buffered && !streamRead;
    }

    /**
     * Check that the entity can be queried: like {@link #checkCanReadEntity()}, but the entity
     * stream that was read is still the entity of the response, which is not closed.
     */
    final void checkCanQueryEntity() {
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
    final void checkCanReadEntity() {
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

    @Override
    public boolean bufferEntity() {
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
     * The bytes of the entity that is buffered.
     *
     * @return The bytes, {@code null} if there is no entity
     */
    byte @Nullable [] bufferedBytes() {
        return response.getBody(byte[].class).orElse(null);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            release();
        }
    }

    /**
     * Release the resources of the entity, e.g. its stream.
     */
    void release() {
        // the entity of a response is in memory
    }

    @Override
    public @Nullable MediaType getMediaType() {
        return jaxRsHttpHeaders.getMediaType();
    }

    @Override
    public @Nullable Locale getLanguage() {
        return jaxRsHttpHeaders.getLanguage();
    }

    @Override
    public int getLength() {
        return jaxRsHttpHeaders.getLength();
    }

    @Override
    public Set<String> getAllowedMethods() {
        // a header line lists methods separated by commas (RFC 9110 section 10.2.1)
        return response.getHeaders().getAll(HttpHeaders.ALLOW)
            .stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(method -> !method.isEmpty())
            .map(String::toUpperCase)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Map<String, NewCookie> getCookies() {
        RuntimeDelegate.HeaderDelegate<NewCookie> newCookieHeaderDelegate = getInstance().createHeaderDelegate(NewCookie.class);
        return response.getHeaders().getAll(jakarta.ws.rs.core.HttpHeaders.SET_COOKIE)
            .stream()
            .map(newCookieHeaderDelegate::fromString)
            // the last cookie of a name wins, like a user agent replaces it
            .collect(Collectors.collectingAndThen(
                Collectors.toMap(Cookie::getName, c -> c, (first, last) -> last, LinkedHashMap::new),
                Collections::unmodifiableMap));
    }

    @Override
    public @Nullable EntityTag getEntityTag() {
        return response.getHeaders()
            .getFirst(HttpHeaders.ETAG)
            .map(entityTag -> getInstance().createHeaderDelegate(EntityTag.class).fromString(entityTag))
            .orElse(null);
    }

    @Override
    public @Nullable Date getDate() {
        return jaxRsHttpHeaders.getDate();
    }

    @Override
    public @Nullable Date getLastModified() {
        return response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED, Date.class).orElse(null);
    }

    @Override
    public @Nullable URI getLocation() {
        return response.getHeaders()
            .getFirst(HttpHeaders.LOCATION)
            .map(URI::create).orElse(null);
    }

    @Override
    public Set<Link> getLinks() {
        // a relative link is resolved against the URI of the request, when it is known
        URI requestUri = response.getAttribute(REQUEST_URI, URI.class).orElse(null);
        return response.getHeaders()
            .getAll(HttpHeaders.LINK)
            .stream()
            .flatMap(value -> LinkDelegate.parseAll(value).stream())
            .map(link -> requestUri == null || link.getUri().isAbsolute() ? link : new LinkImpl(requestUri.resolve(link.getUri()), link.getParams()))
            .collect(Collectors.toSet());
    }

    @Override
    public boolean hasLink(String relation) {
        return getLinks().stream().anyMatch(link -> link.getRels().contains(relation));
    }

    @Override
    public @Nullable Link getLink(String relation) {
        return getLinks().stream().filter(link -> link.getRels().contains(relation)).findFirst().orElse(null);
    }

    @Override
    public Link.@Nullable Builder getLinkBuilder(String relation) {
        Link link = getLink(relation);
        if (link == null) {
            return null;
        }
        return Link.fromLink(link);
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        return jaxRsHttpHeaders.getRequestHeaders();
    }

    @Override
    public @Nullable String getHeaderString(String name) {
        return jaxRsHttpHeaders.getHeaderString(name);
    }

    @Override
    public MultivaluedMap<String, Object> getMetadata() {
        return getHeaders();
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        return new JaxRsObjectHeadersMultivaluedMap(response.getHeaders());
    }
}
