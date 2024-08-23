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
import io.micronaut.http.cookie.Cookie;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    private final MutableHttpResponse<Object> response;

    JaxRsResponseBuilder() {
        this(HttpResponse.ok());
    }

    JaxRsResponseBuilder(MutableHttpResponse<Object> response) {
        this.response = response;
    }

    @Override
    public Response build() {
        MutableHttpResponse<Object> mutableHttpResponse = HttpResponse.status(response.code(), response.reason())
            .body(response.getBody().orElse(null))
            .headers(newHeaders -> response.getHeaders().forEachValue(newHeaders::add));
        return new JaxRsMutableResponse(mutableHttpResponse);
    }

    @Override
    public Response.ResponseBuilder clone() {
        return new JaxRsResponseBuilder();
    }

    @Override
    public Response.ResponseBuilder status(int status) {
        response.status(status);
        return this;
    }

    @Override
    public Response.ResponseBuilder status(int status, String reasonPhrase) {
        response.status(status, reasonPhrase);
        return this;
    }

    @Override
    public Response.ResponseBuilder entity(Object entity) {
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
        header(io.micronaut.http.HttpHeaders.CACHE_CONTROL, cacheControl);
        return this;
    }

    @Override
    public Response.ResponseBuilder encoding(String encoding) {
        header(io.micronaut.http.HttpHeaders.CONTENT_ENCODING, encoding);
        return this;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public Response.ResponseBuilder header(String name, Object value) {
        if (value != null) {
            if (value instanceof Date date) {
                response.getHeaders().add(name, ZonedDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()));
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
                    if (object != null) {
                        response.getHeaders().add(s, object.toString());
                    }
                }
            });
            return this;
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder language(String language) {
        response.header(io.micronaut.http.HttpHeaders.CONTENT_LANGUAGE, language);
        return this;
    }

    @Override
    public Response.ResponseBuilder language(Locale language) {
        response.header(io.micronaut.http.HttpHeaders.CONTENT_LANGUAGE, language.toLanguageTag());
        return this;
    }

    @Override
    public Response.ResponseBuilder type(MediaType type) {
        if (type == null) {
            response.getHeaders().remove(io.micronaut.http.HttpHeaders.CONTENT_TYPE);
        } else {
            response.contentType(new io.micronaut.http.MediaType(type.toString()));
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder type(String type) {
        if (type == null) {
            response.getHeaders().remove(io.micronaut.http.HttpHeaders.CONTENT_TYPE);
        } else {
            response.contentType(type);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder variant(Variant variant) {
        return this;
    }

    @Override
    public Response.ResponseBuilder contentLocation(URI location) {
        header(HttpHeaders.CONTENT_LOCATION, location);
        return this;
    }

    @Override
    public Response.ResponseBuilder cookie(NewCookie... cookies) {
        if (cookies == null) {
            response.getHeaders().remove(HttpHeaders.SET_COOKIE);
            return this;
        }
        for (NewCookie cookie : cookies) {
            final Cookie c = Cookie.of(cookie.getName(), cookie.getValue());
            final String domain = cookie.getDomain();
            if (domain != null) {
                c.domain(domain);
            }
            final String path = cookie.getPath();
            if (path != null) {
                c.path(path);
            }
            final Date expiry = cookie.getExpiry();
            if (expiry != null) {
                long maxAge = expiry.getTime() - new Date().getTime();
                if (maxAge < 0) {
                    throw new IllegalArgumentException("Expiry should not be in the past");
                } else {
                    c.maxAge(maxAge);
                }

            }
            response.cookie(c);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder expires(Date expires) {
        final MutableHttpHeaders headers = response.getHeaders();
        if (expires == null) {
            headers.remove(io.micronaut.http.HttpHeaders.EXPECT);
        } else {
            headers.expires(expires.getTime());
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder lastModified(Date lastModified) {
        final MutableHttpHeaders headers = response.getHeaders();
        if (lastModified == null) {
            headers.remove(io.micronaut.http.HttpHeaders.LAST_MODIFIED);
        } else {
            headers.lastModified(lastModified.getTime());
        }
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
    public Response.ResponseBuilder tag(EntityTag tag) {
        if (tag != null) {
            response.getHeaders().set(io.micronaut.http.HttpHeaders.ETAG,
                getInstance().createHeaderDelegate(EntityTag.class).toString(tag));
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder tag(String tag) {
        if (tag != null) {
            response.getHeaders().set(io.micronaut.http.HttpHeaders.ETAG, tag);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder variants(Variant... variants) {
        return this;
    }

    @Override
    public Response.ResponseBuilder variants(List<Variant> variants) {
        return this;
    }

    @Override
    public Response.ResponseBuilder links(Link... links) {
        final MutableHttpHeaders headers = response.getHeaders();
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
