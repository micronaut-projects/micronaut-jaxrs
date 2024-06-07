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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.jaxrs.runtime.ext.impl.JaxRsArgumentUtils;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriBuilderException;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Partial implementation of {@link UriBuilder}. Unsupported methods throw {@link UnsupportedOperationException}.
 *
 * @author graemerocher
 * @since 1.0
 */
public class JaxRsUriBuilder extends UriBuilder {

    private final io.micronaut.http.uri.UriBuilder uriBuilder;

    /**
     * Default constructor.
     */
    public JaxRsUriBuilder() {
        this(io.micronaut.http.uri.UriBuilder.of("/"));
    }

    /**
     * Copy constructor.
     * @param uriBuilder The uri builder
     */
    JaxRsUriBuilder(io.micronaut.http.uri.UriBuilder uriBuilder) {
        this.uriBuilder = uriBuilder;
    }

    @Override
    public UriBuilder clone() {
        return new JaxRsUriBuilder(io.micronaut.http.uri.UriBuilder.of(uriBuilder.build()));
    }

    @Override
    public UriBuilder uri(URI uri) {
        JaxRsArgumentUtils.requireNonNull("uri", uri);
        uriBuilder.replacePath(uri.getPath());
        uriBuilder.scheme(uri.getScheme());
        uriBuilder.port(uri.getPort());
        uriBuilder.fragment(uri.getFragment());
        uriBuilder.userInfo(uri.getUserInfo());
        uriBuilder.host(uri.getHost());
        return this;
    }

    @Override
    public UriBuilder uri(String uriTemplate) {
        JaxRsArgumentUtils.requireNonNull("uriTemplate", uriTemplate);
        return uri(URI.create(uriTemplate));
    }

    @Override
    public UriBuilder scheme(String scheme) {
        uriBuilder.scheme(scheme);
        return this;
    }

    @Override
    public UriBuilder schemeSpecificPart(String ssp) {
        throw new UnsupportedOperationException("Method schemeSpecificPart(..) not supported by implementation");
    }

    @Override
    public UriBuilder userInfo(String ui) {
        uriBuilder.userInfo(ui);
        return this;
    }

    @Override
    public UriBuilder host(String host) {
        uriBuilder.host(host);
        return this;
    }

    @Override
    public UriBuilder port(int port) {
        uriBuilder.port(port);
        return this;
    }

    @Override
    public UriBuilder replacePath(String path) {
        uriBuilder.replacePath(path == null ? "" : path);
        return this;
    }

    @Override
    public UriBuilder path(String path) {
        JaxRsArgumentUtils.requireNonNull("path", path);
        uriBuilder.path(path);
        return this;
    }

    @Override
    public UriBuilder path(Class resource) {
        JaxRsArgumentUtils.requireNonNull("resource", resource);
        final Path annotation = (Path) resource.getAnnotation(Path.class);
        if (annotation != null) {
            path(annotation.value());
        } else {
            throw new IllegalArgumentException("Resource not annotated with @Path");
        }

        return this;
    }

    @Override
    public UriBuilder path(Class resource, String method) {
        JaxRsArgumentUtils.requireNonNull("resource", resource);
        JaxRsArgumentUtils.requireNonNull("method", method);
        List<Method> candidates = Stream.of(resource.getMethods())
            .filter(m -> m.getName().equals(method) && m.isAnnotationPresent(Path.class))
            .toList();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No such method or not annotated with @Path");
        } else if (candidates.size() > 1) {
            throw new IllegalArgumentException("Multiple method candidates");
        }
        return path(candidates.get(0));
    }

    @Override
    public UriBuilder path(Method method) {
        JaxRsArgumentUtils.requireNonNull("method", method);
        final Path annotation = method.getAnnotation(Path.class);
        if (annotation != null) {
            path(annotation.value());
        } else {
            throw new IllegalArgumentException("Resource not annotated with @Path");
        }
        return this;
    }

    @Override
    public UriBuilder segment(String... segments) {
        JaxRsArgumentUtils.requireNonNull("segments", segments);
        for (String segment : segments) {
            JaxRsArgumentUtils.requireNonNull("segment[*]", segment);
        }
        return path(String.join("/", segments));
    }

    @Override
    public UriBuilder replaceMatrix(String matrix) {
        throw new UnsupportedOperationException("Method replaceMatrix(..) not supported by implementation");
    }

    @Override
    public UriBuilder matrixParam(String name, Object... values) {
        throw new UnsupportedOperationException("Method matrixParam(..) not supported by implementation");
    }

    @Override
    public UriBuilder replaceMatrixParam(String name, Object... values) {
        throw new UnsupportedOperationException("Method replaceMatrixParam(..) not supported by implementation");
    }

    @Override
    public UriBuilder replaceQuery(String query) {
        throw new UnsupportedOperationException("Method replaceQuery(..) not supported by implementation");
    }

    @Override
    public UriBuilder queryParam(String name, Object... values) {
        JaxRsArgumentUtils.requireNonNull("name", name);
        for (Object value : values) {
            JaxRsArgumentUtils.requireNonNull("values[*]", value);
        }
        uriBuilder.queryParam(name, values);
        return this;
    }

    @Override
    public UriBuilder replaceQueryParam(String name, Object... values) {
        if (values == null) {
            throw new UnsupportedOperationException("Removing query params not supported");
        } else {
            uriBuilder.replaceQueryParam(name, values);
        }
        return this;
    }

    @Override
    public UriBuilder fragment(String fragment) {
        uriBuilder.fragment(fragment);
        return this;
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value) {
        throw new UnsupportedOperationException("Method resolveTemplate(..) not supported by implementation");
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        throw new UnsupportedOperationException("Method resolveTemplate(..) not supported by implementation");
    }

    @Override
    public UriBuilder resolveTemplateFromEncoded(String name, Object value) {
        throw new UnsupportedOperationException("Method resolveTemplateFromEncoded(..) not supported by implementation");
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues) {
        throw new UnsupportedOperationException("Method resolveTemplates(..) not supported by implementation");
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) throws IllegalArgumentException {
        throw new UnsupportedOperationException("Method resolveTemplates(..) not supported by implementation");
    }

    @Override
    public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        throw new UnsupportedOperationException("Method resolveTemplateFromEncoded(..) not supported by implementation");
    }

    @Override
    public URI buildFromMap(Map<String, ?> values) {
        return buildFromMap(values, true);
    }

    @Override
    public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        for (Object v : values.values()) {
            JaxRsArgumentUtils.requireNonNull("values[*]", v);
        }
        return uriBuilder.expand((Map<String, ? super Object>) values);
    }

    @Override
    public URI buildFromEncodedMap(Map<String, ?> values) throws IllegalArgumentException, UriBuilderException {
        return uriBuilder.expand((Map<String, ? super Object>) values);
    }

    @Override
    public URI build(Object... values) throws IllegalArgumentException, UriBuilderException {
        return uriBuilder.build();
    }

    @Override
    public URI build(Object[] values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        return uriBuilder.build();
    }

    @Override
    public URI buildFromEncoded(Object... values) throws IllegalArgumentException, UriBuilderException {
        return uriBuilder.build();
    }

    @Override
    public String toTemplate() {
        return uriBuilder.toString();
    }
}
