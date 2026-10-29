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
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseProvider;
import io.micronaut.http.MutableHttpResponse;
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

    private final HttpResponse<?> response;
    private final JaxRsHttpHeaders jaxRsHttpHeaders;
    private final JaxRsResponseEntity entity;

    public JaxRsResponse(HttpResponse<?> response) {
        this(response, HttpMessageEntityReader.DEFAULT);
    }

    public JaxRsResponse(HttpResponse<?> response,
                         HttpMessageEntityReader entityReader) {
        this(response, null, entityReader);
    }

    /**
     * @param response        The response
     * @param mutableResponse The same response, when it is mutable
     * @param entityReader    The reader of the entity
     */
    JaxRsResponse(HttpResponse<?> response, @Nullable MutableHttpResponse<?> mutableResponse, HttpMessageEntityReader entityReader) {
        this.response = response;
        this.jaxRsHttpHeaders = JaxRsHttpHeaders.forResponse(response.getHeaders());
        this.entity = new JaxRsResponseEntity(response, mutableResponse, entityReader);
    }

    /**
     * @return The entity and its lifecycle
     */
    final JaxRsResponseEntity entity() {
        return entity;
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
        return entity.getEntity();
    }

    /**
     * Read the entity.
     *
     * @param entityType The entity argument
     * @param <T>        The entity type
     * @return The entity value
     */
    public <T> @Nullable T readEntity(Argument<T> entityType) {
        return entity.readEntity(entityType);
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
        return entity.hasEntity();
    }

    @Override
    public boolean isClosed() {
        return entity.isClosed();
    }

    @Override
    public boolean bufferEntity() {
        return entity.bufferEntity();
    }

    @Override
    public void close() {
        entity.close();
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
