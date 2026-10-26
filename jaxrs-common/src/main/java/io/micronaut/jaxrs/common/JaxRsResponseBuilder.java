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
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import org.jspecify.annotations.Nullable;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static jakarta.ws.rs.ext.RuntimeDelegate.getInstance;

/**
 * Implementation of {@link jakarta.ws.rs.core.Response.ResponseBuilder} for Micronaut.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsResponseBuilder extends Response.ResponseBuilder {

    private MutableHttpResponse<Object> response;
    // the objects of the headers, e.g. a Date to the millisecond, see JaxRsResponseMetadata
    private JaxRsResponseMetadata.State metadata;
    // whether a status was selected: a response without one and without an entity is a 204 (JAX-RS 3.3.3)
    private boolean statusSet;

    JaxRsResponseBuilder() {
        this(HttpResponse.ok());
    }

    JaxRsResponseBuilder(MutableHttpResponse<Object> response) {
        this(response, new JaxRsResponseMetadata.State());
    }

    private JaxRsResponseBuilder(MutableHttpResponse<Object> response, JaxRsResponseMetadata.State metadata) {
        this.response = response;
        this.metadata = metadata;
    }

    @Override
    public Response build() {
        if (!statusSet && response.getBody().isEmpty()) {
            response.status(Response.Status.NO_CONTENT.getStatusCode());
        }
        Response built = new JaxRsMutableResponse(response, metadata);
        // the builder is reset to a blank state, like ok()
        response = HttpResponse.ok();
        metadata = new JaxRsResponseMetadata.State();
        statusSet = true;
        return built;
    }

    @Override
    public Response.ResponseBuilder clone() {
        // a builder of the same response, which changes independently of this one
        MutableHttpResponse<Object> copy = HttpResponse.status(response.code(), response.reason())
            .body(response.getBody().orElse(null))
            .headers(newHeaders -> response.getHeaders().forEachValue(newHeaders::add));
        JaxRsResponseBuilder clone = new JaxRsResponseBuilder(copy, metadata.copy());
        clone.statusSet = statusSet;
        return clone;
    }

    @Override
    public Response.ResponseBuilder status(int status) {
        checkStatus(status);
        response.status(status);
        statusSet = true;
        return this;
    }

    @Override
    public Response.ResponseBuilder status(int status, String reasonPhrase) {
        checkStatus(status);
        response.status(status, reasonPhrase);
        statusSet = true;
        return this;
    }

    private static void checkStatus(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Status out of the range 100..599: " + status);
        }
    }

    @Override
    public Response.ResponseBuilder entity(Object entity) {
        // a status that was selected is kept, even for a null entity
        response.body(entity);
        return this;
    }

    @Override
    public Response.ResponseBuilder entity(Object entity, Annotation[] annotations) {
        entity(new JaxRsGenericEntity<>(entity, annotations));
        return this;
    }

    @Override
    public Response.ResponseBuilder allow(String... methods) {
        if (methods == null) {
            response.getHeaders().remove(HttpHeaders.ALLOW);
        } else {
            response.getHeaders().allowGeneric(Arrays.asList(methods));
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder allow(Set<String> methods) {
        if (methods == null) {
            response.getHeaders().remove(HttpHeaders.ALLOW);
        } else {
            response.getHeaders().allowGeneric(methods);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder cacheControl(CacheControl cacheControl) {
        replaceHeader(io.micronaut.http.HttpHeaders.CACHE_CONTROL, cacheControl);
        return this;
    }

    @Override
    public Response.ResponseBuilder encoding(@Nullable String encoding) {
        replaceHeader(io.micronaut.http.HttpHeaders.CONTENT_ENCODING, encoding);
        return this;
    }

    /**
     * Sets a header: unlike {@link #header(String, Object)}, which adds a value, it replaces the
     * values of the header, removed for a null value.
     */
    private void replaceHeader(String name, @Nullable Object value) {
        response.getHeaders().remove(name);
        header(name, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public Response.ResponseBuilder header(String name, @Nullable Object value) {
        if (value != null) {
            if (value instanceof Date date) {
                // an IMF-fixdate (RFC 9110 5.6.7)
                response.getHeaders().add(name, DateHeaderDelegate.INSTANCE.toString(date));
                metadata.set(response.getHeaders(), name, date);
            } else {
                RuntimeDelegate.HeaderDelegate headerDelegate = getInstance().createHeaderDelegate(value.getClass());
                if (headerDelegate == null) {
                    response.header(name, value.toString());
                } else {
                    String stringValue = headerDelegate.toString(value);
                    response.header(name, stringValue == null ? "" : stringValue);
                }
            }
        } else {
            response.getHeaders().remove(name);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> headers) {
        for (String k : List.copyOf(response.getHeaders().names())) {
            response.getHeaders().remove(k);
        }
        if (headers != null) {
            headers.forEach((s, objects) -> {
                for (Object object : objects) {
                    // through the header delegates, e.g. a date as an HTTP date
                    if (object != null) {
                        header(s, object);
                    }
                }
            });
            return this;
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder language(@Nullable String language) {
        if (language == null) {
            response.getHeaders().remove(io.micronaut.http.HttpHeaders.CONTENT_LANGUAGE);
        } else {
            // replaces the language, e.g. of a variant
            response.getHeaders().set(io.micronaut.http.HttpHeaders.CONTENT_LANGUAGE, language);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder language(@Nullable Locale language) {
        return language(language == null ? null : language.toLanguageTag());
    }

    @Override
    public Response.ResponseBuilder type(@Nullable MediaType type) {
        if (type == null) {
            response.getHeaders().remove(io.micronaut.http.HttpHeaders.CONTENT_TYPE);
        } else {
            response.contentType(new io.micronaut.http.MediaType(type.toString()));
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder type(@Nullable String type) {
        if (type == null) {
            response.getHeaders().remove(io.micronaut.http.HttpHeaders.CONTENT_TYPE);
        } else {
            response.contentType(type);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder variant(@Nullable Variant variant) {
        // the media type, language and encoding of the variant, none for a null one
        if (variant == null) {
            type((MediaType) null);
            language((String) null);
            encoding(null);
        } else {
            type(variant.getMediaType());
            language(variant.getLanguageString());
            encoding(variant.getEncoding());
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder contentLocation(URI location) {
        replaceHeader(HttpHeaders.CONTENT_LOCATION, location);
        return this;
    }

    @Override
    public Response.ResponseBuilder cookie(NewCookie... cookies) {
        if (cookies == null) {
            response.getHeaders().remove(HttpHeaders.SET_COOKIE);
            metadata.set(response.getHeaders(), HttpHeaders.SET_COOKIE, null);
            return this;
        }
        RuntimeDelegate.HeaderDelegate<NewCookie> delegate = getInstance().createHeaderDelegate(NewCookie.class);
        // the cookies set as a raw header are kept
        metadata.sync(response.getHeaders(), HttpHeaders.SET_COOKIE);
        for (NewCookie cookie : cookies) {
            // all the attributes of the cookie, e.g. its max age in seconds, an expiry in the past (a deletion), its version
            response.getHeaders().add(HttpHeaders.SET_COOKIE, delegate.toString(cookie));
            // the metadata has the cookie, e.g. with its version
            metadata.add(response.getHeaders(), HttpHeaders.SET_COOKIE, cookie);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder expires(Date expires) {
        final MutableHttpHeaders headers = response.getHeaders();
        if (expires == null) {
            headers.remove(io.micronaut.http.HttpHeaders.EXPIRES);
        } else {
            headers.set(io.micronaut.http.HttpHeaders.EXPIRES, DateHeaderDelegate.INSTANCE.toString(expires));
        }
        metadata.set(headers, io.micronaut.http.HttpHeaders.EXPIRES, expires);
        return this;
    }

    @Override
    public Response.ResponseBuilder lastModified(Date lastModified) {
        final MutableHttpHeaders headers = response.getHeaders();
        if (lastModified == null) {
            headers.remove(io.micronaut.http.HttpHeaders.LAST_MODIFIED);
        } else {
            headers.set(io.micronaut.http.HttpHeaders.LAST_MODIFIED, DateHeaderDelegate.INSTANCE.toString(lastModified));
        }
        metadata.set(headers, io.micronaut.http.HttpHeaders.LAST_MODIFIED, lastModified);
        return this;
    }

    @Override
    public Response.ResponseBuilder location(URI location) {
        final MutableHttpHeaders headers = response.getHeaders();
        if (location == null) {
            headers.remove(io.micronaut.http.HttpHeaders.LOCATION);
        } else {
            if (location.isAbsolute()) {
                headers.location(location);
            } else {
                // TODO: Apply relative path to base URL
                headers.location(location);
            }
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder tag(@Nullable EntityTag tag) {
        if (tag == null) {
            response.getHeaders().remove(io.micronaut.http.HttpHeaders.ETAG);
        } else {
            response.getHeaders().set(io.micronaut.http.HttpHeaders.ETAG,
                getInstance().createHeaderDelegate(EntityTag.class).toString(tag));
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder tag(@Nullable String tag) {
        // a strong entity tag of the value, quoted
        return tag(tag == null ? null : new EntityTag(tag));
    }

    @Override
    public Response.ResponseBuilder variants(Variant @Nullable ... variants) {
        return variants(variants == null ? null : Arrays.asList(variants));
    }

    @Override
    public Response.ResponseBuilder variants(@Nullable List<Variant> variants) {
        if (variants == null) {
            response.getHeaders().remove(HttpHeaders.VARY);
            return this;
        }
        // Vary names the request headers the variants differ in
        StringBuilder vary = new StringBuilder();
        appendVary(vary, variants.stream().map(Variant::getMediaType).toList(), HttpHeaders.ACCEPT);
        appendVary(vary, variants.stream().map(Variant::getLanguage).toList(), HttpHeaders.ACCEPT_LANGUAGE);
        appendVary(vary, variants.stream().map(Variant::getEncoding).toList(), HttpHeaders.ACCEPT_ENCODING);
        if (!vary.isEmpty()) {
            response.getHeaders().add(HttpHeaders.VARY, vary.toString());
        }
        return this;
    }

    private static void appendVary(StringBuilder vary, List<?> values, String header) {
        Object first = null;
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (first == null) {
                first = value;
            } else if (!first.equals(value)) {
                if (!vary.isEmpty()) {
                    vary.append(',');
                }
                vary.append(header);
                return;
            }
        }
    }

    @Override
    public Response.ResponseBuilder links(Link @Nullable ... links) {
        final MutableHttpHeaders headers = response.getHeaders();
        if (links == null) {
            // null removes the links
            headers.remove(HttpHeaders.LINK);
            return this;
        }
        for (Link link : links) {
            headers.add(HttpHeaders.LINK, link.toString());
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder link(URI uri, String rel) {
        ArgumentUtils.requireNonNull("uri", uri);
        ArgumentUtils.requireNonNull("rel", rel);
        final Link link = Link.fromUri(uri).rel(rel).build();
        response.getHeaders().add(HttpHeaders.LINK, link.toString());
        return this;
    }

    @Override
    public Response.ResponseBuilder link(String uri, String rel) {
        return link(URI.create(uri), rel);
    }

}
