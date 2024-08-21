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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseProvider;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    private final HttpResponse<?> response;
    private final HttpMessageEntityReader entityReader;
    private final JaxRsHttpHeaders jaxRsHttpHeaders;
    private boolean buffered;
    private boolean closed;
    private byte[] buffer;
    private Argument<?> readBodyArgument;
    private Object readBody;

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
    public Object getEntity() {
        checkCanReadEntity();
        return response.body();
    }

    /**
     * Read the entity.
     *
     * @param entityType The entity argument
     * @param <T>        The entity type
     * @return The entity value
     */
    public <T> T readEntity(Argument<T> entityType) {
        if (readBodyArgument != null && readBodyArgument.getType().equals(entityType.getType())) {
            return (T) readBody;
        }
        checkCanReadEntity();
        try {
            if (buffered) {
                return entityReader.readEntity(response.toMutableResponse().body(buffer), entityType);
            } else {
                readBodyArgument = entityType;
                readBody = entityReader.readEntity(response, entityType);
                return (T) readBody;
            }
        } finally {
            close();
        }
    }

    @Override
    public <T> T readEntity(Class<T> entityType) {
        return readEntity(Argument.of(entityType));
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType) {
        return readEntity(JaxRsArgumentUtil.from(entityType));
    }

    @Override
    public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
        return readEntity(JaxRsArgumentUtil.from(entityType, annotations));
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
        return readEntity(JaxRsArgumentUtil.from(entityType, annotations));
    }

    @Override
    public boolean hasEntity() {
        checkCanReadEntity();
        return response.getBody().isPresent();
    }

    private void checkCanReadEntity() {
        if (closed && !buffered) {
            throw new IllegalStateException("Response closed");
        }
    }

    @Override
    public boolean bufferEntity() {
        if (!buffered) {
            checkCanReadEntity();
            Optional<byte[]> body = response.getBody(byte[].class);
            if (body.isEmpty()) {
                return false;
            }
            buffer = body.orElse(new byte[]{});
            buffered = true;
        }
        return buffered;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public MediaType getMediaType() {
        return jaxRsHttpHeaders.getMediaType();
    }

    @Override
    public Locale getLanguage() {
        return jaxRsHttpHeaders.getLanguage();
    }

    @Override
    public int getLength() {
        return jaxRsHttpHeaders.getLength();
    }

    @Override
    public Set<String> getAllowedMethods() {
        return response.getHeaders().getAll(HttpHeaders.ALLOW)
            .stream()
            .map(String::toUpperCase)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Nullable
    public Map<String, NewCookie> getCookies() {
        RuntimeDelegate.HeaderDelegate<NewCookie> newCookieHeaderDelegate = getInstance().createHeaderDelegate(NewCookie.class);
        return response.getHeaders().getAll(jakarta.ws.rs.core.HttpHeaders.SET_COOKIE)
            .stream()
            .map(newCookieHeaderDelegate::fromString)
            .collect(Collectors.toUnmodifiableMap(Cookie::getName, c -> c));
    }

    @Override
    public EntityTag getEntityTag() {
        return response.getHeaders()
            .getFirst(HttpHeaders.ETAG)
            .map(entityTag -> getInstance().createHeaderDelegate(EntityTag.class).fromString(entityTag))
            .orElse(null);
    }

    @Override
    public Date getDate() {
        return jaxRsHttpHeaders.getDate();
    }

    @Override
    public Date getLastModified() {
        return response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED, Date.class).orElse(null);
    }

    @Override
    public URI getLocation() {
        return response.getHeaders()
            .getFirst(HttpHeaders.LOCATION)
            .map(URI::create).orElse(null);
    }

    @Override
    public Set<Link> getLinks() {
        return response.getHeaders()
            .getAll(HttpHeaders.LINK)
            .stream()
            .map(Link::valueOf)
            .collect(Collectors.toSet());
    }

    @Override
    public boolean hasLink(String relation) {
        return getLinks().stream().anyMatch(link -> link.getRel().equals(relation));
    }

    @Override
    public Link getLink(String relation) {
        return getLinks().stream().filter(link -> link.getRel().equals(relation)).findFirst().orElse(null);
    }

    @Override
    public Link.Builder getLinkBuilder(String relation) {
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
    public String getHeaderString(String name) {
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
