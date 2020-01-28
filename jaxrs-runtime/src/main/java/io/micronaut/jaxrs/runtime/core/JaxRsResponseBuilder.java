package io.micronaut.jaxrs.runtime.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.cookie.Cookie;

import javax.ws.rs.core.*;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.*;

/**
 * Implementation of {@link javax.ws.rs.core.Response.ResponseBuilder} for Micronaut.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public class JaxRsResponseBuilder extends Response.ResponseBuilder {

    private final JaxRsResponse micronautResponse = new JaxRsResponse();
    private final MutableHttpResponse<Object> response = micronautResponse.getResponse();

    @Override
    public Response build() {
        return micronautResponse;
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
    public Response.ResponseBuilder entity(Object entity) {
        response.body(entity);
        return this;
    }

    @Override
    public Response.ResponseBuilder entity(Object entity, Annotation[] annotations) {
        entity(entity);
        return this;
    }

    @Override
    public Response.ResponseBuilder allow(String... methods) {
        response.getHeaders().allowGeneric(Arrays.asList(methods));
        return this;
    }

    @Override
    public Response.ResponseBuilder allow(Set<String> methods) {
        response.getHeaders().allowGeneric(methods);
        return this;
    }

    @Override
    public Response.ResponseBuilder cacheControl(CacheControl cacheControl) {
        if (cacheControl != null) {
            response.getHeaders().add(
                    io.micronaut.http.HttpHeaders.CACHE_CONTROL, cacheControl.toString()
            );
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder encoding(String encoding) {
        response.header(io.micronaut.http.HttpHeaders.CONTENT_ENCODING, encoding);
        return this;
    }

    @Override
    public Response.ResponseBuilder header(String name, Object value) {
        if (value != null) {
            response.header(name, value.toString());
        } else {
            response.getHeaders().remove(name);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> headers) {
        final MutableHttpHeaders finalHeaders = response.getHeaders();
        headers.forEach((s, objects) -> {
            finalHeaders.remove(s);
            for (Object object : objects) {
                if (object != null) {
                    finalHeaders.add(s, object.toString());
                }
            }
        });
        return this;
    }

    @Override
    public Response.ResponseBuilder language(String language) {
        response.header(io.micronaut.http.HttpHeaders.CONTENT_LANGUAGE, language);
        return this;
    }

    @Override
    public Response.ResponseBuilder language(Locale language) {
        response.header(io.micronaut.http.HttpHeaders.CONTENT_LANGUAGE, language.toString());
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
        response.getHeaders().location(location);
        return this;
    }

    @Override
    public Response.ResponseBuilder cookie(NewCookie... cookies) {
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
            headers.location(location);
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder tag(EntityTag tag) {
        if (tag != null) {
            response.getHeaders().add(io.micronaut.http.HttpHeaders.ETAG, tag.toString());
        }
        return this;
    }

    @Override
    public Response.ResponseBuilder tag(String tag) {
        if (tag != null) {
            response.getHeaders().add(io.micronaut.http.HttpHeaders.ETAG, tag);
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
