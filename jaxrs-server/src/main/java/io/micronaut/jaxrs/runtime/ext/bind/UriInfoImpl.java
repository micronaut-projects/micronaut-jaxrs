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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.web.router.RouteMatch;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The JAX-RS {@link UriInfo} injected through {@link jakarta.ws.rs.core.Context} annotation.
 *
 * @author Dan Hollingsworth
 * @since 3.3.0
 */
@Internal
public final class UriInfoImpl implements UriInfo {
    private final HttpRequest<?> request;
    private final String basePath;

    /**
     * Construct from an HTTP request.
     *
     * @param request  The HTTP request to this URI
     * @param basePath The base path
     */
    public UriInfoImpl(@NonNull HttpRequest<?> request, @Nullable String basePath) {
        this.request = request;
        this.basePath = basePath == null || basePath.equals("/") ? null : basePath;
    }

    /**
     * Construct from an HTTP request.
     *
     * @param request The HTTP request to this URI
     */
    public UriInfoImpl(@NonNull HttpRequest<?> request) {
        this(request, null);
    }

    private String getPath(String requestPath, boolean decode) {
        String path = decode ? URLDecoder.decode(requestPath, StandardCharsets.UTF_8) : requestPath;
        if (basePath != null) {
            String pathToCheck = path;
            if (!path.startsWith("/")) {
                pathToCheck = "/" + path;
            }
            if (pathToCheck.startsWith(basePath)) {
                return pathToCheck.substring(basePath.length());
            }
        }
        return path;
    }

    @Override
    public String getPath() {
        return getPath(true);
    }

    @Override
    public String getPath(boolean decode) {
        return getPath(request.getPath(), decode);
    }

    @Override
    public List<PathSegment> getPathSegments() {
        return getPathSegments(true);
    }

    @Override
    public List<PathSegment> getPathSegments(boolean decode) {
        return Stream.of(request.getPath().split("/"))
            .filter(StringUtils::isNotEmpty)
            .<PathSegment>map(token -> {
                String[] segmentTokens = token.split(";");
                MultivaluedMap<String, String> params = new MultiMapNullPermitted<>();
                for (int i = 1; i < segmentTokens.length; ++i) {
                    String[] keyVal = segmentTokens[i].split("=", 2);
                    String key = keyVal[0];
                    String val = keyVal.length > 1 ? keyVal[1] : null;
                    params.add(getPath(key, decode), getPath(val, decode));
                }
                return new UriPathSegment(getPath(segmentTokens[0], decode), params);
            })
            .toList();
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

    @Override
    public MultivaluedMap<String, String> getPathParameters() {
        return getPathParameters(true);
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters(boolean decode) {
        RouteMatch<?> match = request.getAttribute(HttpAttributes.ROUTE_MATCH, RouteMatch.class)
            .orElseThrow(() -> new IllegalStateException("Route match not available!"));
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        if (decode) {
            match.getVariableValues().forEach((name, value) -> map.add(name, value.toString()));
        } else {
            // We should be able to access DefaultUriRouteMatch#matchInfo to get unencoded values
            match.getVariableValues().forEach((name, value) -> map.add(
                name,
                URLEncoder.encode(value.toString(), StandardCharsets.UTF_8).replace("+", "%20")
            ));
        }
        return map;
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters() {
        return getQueryParameters(true);
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        if (decode) {
            request.getParameters().forEach(
                (str, vals) -> vals.forEach(
                    val -> map.add(getPath(str, decode), getPath(val, decode))));
        } else {
            getEncodedParameters(request.getUri()).forEach(
                (str, vals) -> vals.forEach(
                    val -> map.add(str, val)));
        }
        return map;
    }

    public static Map<String, List<String>> getEncodedParameters(URI url) {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        final String[] pairs = url.getRawQuery().split("&");
        for (String pair : pairs) {
            final int idx = pair.indexOf("=");
            final String key = idx > 0 ? pair.substring(0, idx) : pair;
            final String value = idx > 0 && pair.length() > idx + 1 ? pair.substring(idx + 1) : null;
            List<String> list = map.get(key);
            if (list == null) {
                list = new ArrayList<>();
                map.put(key, list);
            }
            list.add(value);
        }
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

    private record UriPathSegment(String path,
                                  MultivaluedMap<String, String> params) implements PathSegment {
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
    private static final class MultiMapNullPermitted<K, V> extends MultivaluedHashMap<K, V> {

        @Override
        protected void addNull(List<V> values) {
            values.add(null);
        }
    }
}
