/*
 * Copyright 2017-2022 original authors
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
package io.micronaut.jaxrs.runtime.ext.bind;

import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpRequest;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The JAX-RS {@link UriInfo} injected through {@link javax.ws.rs.core.Context} annotation.
 *
 * @author Dan Hollingsworth
 * @since 3.3.0
 */
public class UriInfoImpl implements UriInfo {
    private final HttpRequest<?> request;

    /**
     * Construct from an HTTP request.
     *
     * @param request The HTTP request to this URI
     */
    public UriInfoImpl(HttpRequest<?> request) {
        this.request = request;
    }

    private String string(String str, boolean decode) {
        try {
            return decode ? URLDecoder.decode(str, StandardCharsets.UTF_8.toString()) : str;
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Charset not found");
        }
    }

    @Override
    public String getPath() {
        return getPath(true);
    }

    @Override
    public String getPath(boolean decode) {
        return string(request.getPath(), decode);
    }

    @Override
    public List<PathSegment> getPathSegments() {
        return getPathSegments(true);
    }

    @Override
    public List<PathSegment> getPathSegments(boolean decode) {
        return Stream.of(request.getPath().split("/"))
                .filter(StringUtils::isNotEmpty)
                .map(token -> {
                    String[] segmentTokens = token.split(";");
                    MultivaluedMap<String, String> params = new MultiMapNullPermitted<>();
                    for (int i = 1; i < segmentTokens.length; ++i) {
                        String[] keyVal = segmentTokens[i].split("=", 2);
                        String key = keyVal[0];
                        String val = keyVal.length > 1 ? keyVal[1] : null;
                        params.add(string(key, decode), string(val, decode));
                    }
                    return new UriPathSegment(string(segmentTokens[0], decode), params);
                })
                .collect(Collectors.toList());
    }

    @Override
    public URI getRequestUri() {
        return request.getUri();
    }

    /**
     * This operation is not supported currently,
     * so {@link UnsupportedOperationException} is thrown for all invocations.
     *
     * @throws UnsupportedOperationException this operation is not supported currently.
     */
    @Override
    public UriBuilder getRequestUriBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI getAbsolutePath() {
        return getBaseUri().resolve(getPath(false));
    }

    /**
     * This operation is not supported currently,
     * so {@link UnsupportedOperationException} is thrown for all invocations.
     *
     * @throws UnsupportedOperationException this operation is not supported currently.
     */
    @Override
    public UriBuilder getAbsolutePathBuilder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI getBaseUri() {
        URI uri = request.getUri();
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), "", uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Unexpected URI format: " + uri.toASCIIString(), e);
        }
    }

    /**
     * This operation is not supported currently,
     * so {@link UnsupportedOperationException} is thrown for all invocations.
     *
     * @throws UnsupportedOperationException this operation is not supported currently.
     */
    @Override
    public UriBuilder getBaseUriBuilder() {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported currently,
     * so {@link UnsupportedOperationException} is thrown for all invocations.
     *
     * @throws UnsupportedOperationException this operation is not supported currently.
     */
    @Override
    public MultivaluedMap<String, String> getPathParameters() {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported currently,
     * so {@link UnsupportedOperationException} is thrown for all invocations.
     *
     * @throws UnsupportedOperationException this operation is not supported currently.
     */
    @Override
    public MultivaluedMap<String, String> getPathParameters(boolean decode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters() {
        return getQueryParameters(true);
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        request.getParameters().forEach(
                (str, vals) -> vals.forEach(
                        val -> map.add(string(str, decode), string(val, decode))));
        return map;
    }

    /**
     * This operation is not supported currently,
     * so {@link UnsupportedOperationException} is thrown for all invocations.
     *
     * @throws UnsupportedOperationException this operation is not supported currently.
     */
    @Override
    public List<String> getMatchedURIs() {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported currently,
     * so {@link UnsupportedOperationException} is thrown for all invocations.
     *
     * @throws UnsupportedOperationException this operation is not supported currently.
     */
    @Override
    public List<String> getMatchedURIs(boolean decode) {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported currently,
     * so {@link UnsupportedOperationException} is thrown for all invocations.
     *
     * @throws UnsupportedOperationException this operation is not supported currently.
     */
    @Override
    public List<Object> getMatchedResources() {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI resolve(URI uri) {
        return request.getUri().resolve(uri);
    }

    @Override
    public URI relativize(URI uri) {
        return request.getUri().relativize(uri);
    }

    private static class UriPathSegment implements PathSegment {

        final String path;
        final MultivaluedMap<String, String> params;

        UriPathSegment(String path, MultivaluedMap<String, String> params) {
            this.path = path;
            this.params = params;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public MultivaluedMap<String, String> getMatrixParameters() {
            return params;
        }
    }

    /*
     * Users may want to know when an empty value is passed as originally described by
     * Tim Berners-Lee: https://www.w3.org/DesignIssues/MatrixURIs.html
     */
    private static class MultiMapNullPermitted<K, V> extends MultivaluedHashMap<K, V> {
        public MultiMapNullPermitted() {
            super();
        }

        @Override
        protected void addNull(List<V> values) {
            values.add(null);
        }
    }
}
