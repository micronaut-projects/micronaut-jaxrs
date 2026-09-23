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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.jaxrs.container.JaxRsMatched;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteMatch;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import java.net.InetSocketAddress;
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
    @Nullable
    private final String basePath;
    private final String contextPath;

    /**
     * Construct from an HTTP request.
     *
     * @param request  The HTTP request to this URI
     * @param basePath The base path
     */
    public UriInfoImpl(@NonNull HttpRequest<?> request, @Nullable String basePath) {
        this(request, basePath, "");
    }

    /**
     * Construct from an HTTP request.
     *
     * @param request     The request
     * @param basePath    The base path: the context path and the application path
     * @param contextPath The context path of the server
     */
    public UriInfoImpl(@NonNull HttpRequest<?> request, @Nullable String basePath, @NonNull String contextPath) {
        this.request = request;
        this.basePath = basePath == null || basePath.equals("/") ? null : basePath;
        this.contextPath = contextPath;
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
                    params.add(getPath(key, decode), val == null ? null : getPath(val, decode));
                }
                return new UriPathSegment(getPath(segmentTokens[0], decode), params);
            })
            .toList();
    }

    @Override
    public URI getRequestUri() {
        URI uri = request.getUri();
        if (uri.isAbsolute() && uri.getRawAuthority() != null) {
            return uri;
        }
        String rawPath = uri.getRawPath();
        return uriWithRawPath(rawPath == null || rawPath.isEmpty() ? "/" : rawPath, uri.getRawQuery());
    }

    @Override
    public UriBuilder getRequestUriBuilder() {
        return UriBuilder.fromUri(getRequestUri());
    }

    @Override
    public URI getAbsolutePath() {
        String rawPath = request.getUri().getRawPath();
        return uriWithRawPath(rawPath == null || rawPath.isEmpty() ? "/" : rawPath, null);
    }

    @Override
    public UriBuilder getAbsolutePathBuilder() {
        return UriBuilder.fromUri(getAbsolutePath());
    }

    @Override
    public URI getBaseUri() {
        String path = basePath == null ? "/" : basePath.startsWith("/") ? basePath : '/' + basePath;
        return uriWithRawPath(path.endsWith("/") ? path : path + '/', null);
    }

    @Override
    public UriBuilder getBaseUriBuilder() {
        return UriBuilder.fromUri(getBaseUri());
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters() {
        return getPathParameters(true);
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters(boolean decode) {
        RouteMatch<?> match = RouteAttributes.getRouteMatch(request)
            .orElseThrow(() -> new IllegalStateException("Route match not available!"));
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        if (decode) {
            match.getVariableValues().forEach((name, value) -> map.add(variableName(name), value.toString()));
        } else {
            // We should be able to access DefaultUriRouteMatch#matchInfo to get unencoded values
            match.getVariableValues().forEach((name, value) -> map.add(
                variableName(name),
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
        var map = new MultivaluedHashMap<String, String>();
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
        final var map = new LinkedHashMap<String, List<String>>();
        final String[] pairs = url.getRawQuery().split("&");
        for (String pair : pairs) {
            final int idx = pair.indexOf("=");
            final String key = idx > 0 ? pair.substring(0, idx) : pair;
            final String value = idx > 0 && pair.length() > idx + 1 ? pair.substring(idx + 1) : null;
            List<String> list = map.computeIfAbsent(key, k -> new ArrayList<>());
            list.add(value);
        }
        return map;
    }

    @Override
    public List<String> getMatchedURIs() {
        return getMatchedURIs(true);
    }

    @Override
    public String getMatchedResourceTemplate() {
        // the template of the matched route, relative to the context path of the server
        return io.micronaut.web.router.RouteAttributes.getRouteInfo(request)
            .filter(io.micronaut.web.router.UriRouteInfo.class::isInstance)
            .map(route -> io.micronaut.jaxrs.container.JaxRsRouteTemplateEngine.withoutMarks(((io.micronaut.web.router.UriRouteInfo<?, ?>) route).getRouteTemplate().expression()))
            .map(template -> !contextPath.isEmpty() && template.startsWith(contextPath) ? template.substring(contextPath.length()) : template)
            .orElse("");
    }

    @Override
    public List<String> getMatchedURIs(boolean decode) {
        String path = trimSlashes(getPath(decode));
        List<String> uris = new ArrayList<>();
        uris.add(path);
        JaxRsMatched matched = JaxRsMatched.get(request);
        if (matched != null) {
            for (int segments : matched.segments()) {
                if (segments < 0) {
                    // a target located at runtime: its prefix is not known
                    continue;
                }
                String uri = firstSegments(path, segments);
                if (!uri.equals(uris.get(uris.size() - 1))) {
                    uris.add(uri);
                }
            }
        }
        return uris;
    }

    @Override
    public List<Object> getMatchedResources() {
        JaxRsMatched matched = JaxRsMatched.get(request);
        return matched == null ? List.of() : matched.resources();
    }

    @Override
    public URI resolve(URI uri) {
        return request.getUri().resolve(uri);
    }

    @Override
    public URI relativize(URI uri) {
        return request.getUri().relativize(uri);
    }

    /**
     * An absolute URI of this request with another path and query.
     */
    private URI uriWithRawPath(String rawPath, @Nullable String rawQuery) {
        URI uri = request.getUri();
        StringBuilder builder = new StringBuilder();
        String scheme = uri.getScheme();
        String rawAuthority = uri.getRawAuthority();
        if (scheme != null && rawAuthority != null) {
            builder.append(scheme).append("://").append(rawAuthority);
        } else {
            scheme = request.isSecure() ? HttpRequest.SCHEME_HTTPS : HttpRequest.SCHEME_HTTP;
            String authority = request.getHeaders().get(HttpHeaders.HOST);
            if (StringUtils.isEmpty(authority)) {
                InetSocketAddress serverAddress = request.getServerAddress();
                String host = request.getServerName();
                if ((host == null || host.isBlank()) && serverAddress != null) {
                    host = serverAddress.getHostString();
                }
                int port = uri.getPort() < 0 && serverAddress != null ? serverAddress.getPort() : uri.getPort();
                if (host != null && !host.isBlank()) {
                    String authorityHost = host.indexOf(':') > -1 && !host.startsWith("[") ? '[' + host + ']' : host;
                    authority = port < 0 ? authorityHost : authorityHost + ':' + port;
                }
            }
            if (StringUtils.isNotEmpty(authority)) {
                for (int i = 0; i < authority.length(); i++) {
                    char c = authority.charAt(i);
                    if (c <= 0x20 || c == 0x7f) {
                        throw new IllegalArgumentException("Invalid URI authority");
                    }
                }
                builder.append(scheme).append("://").append(authority);
            }
        }
        if (!rawPath.startsWith("/")) {
            builder.append('/');
        }
        builder.append(rawPath);
        if (rawQuery != null) {
            builder.append('?').append(rawQuery);
        }
        try {
            return new URI(builder.toString());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Unexpected URI format: " + uri.toASCIIString(), e);
        }
    }

    /**
     * The name of a variable: an earlier occurrence of a variable that repeats has the name of the
     * variable, see {@link io.micronaut.jaxrs.container.JaxRsRouteTemplateEngine#repeated(String, int)}.
     */
    private static String variableName(String name) {
        int mark = name.indexOf(io.micronaut.jaxrs.container.JaxRsRouteTemplateEngine.LOCATOR_MARK);
        return mark < 0 ? name : name.substring(0, mark);
    }

        private static String trimSlashes(String path) {
        int start = path.startsWith("/") ? 1 : 0;
        int end = path.length() > start && path.endsWith("/") ? path.length() - 1 : path.length();
        return path.substring(start, end);
    }

    /**
     * The first path segments of a matched URI.
     */
    private static String firstSegments(String path, int segments) {
        if (segments <= 0) {
            return "";
        }
        int count = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/' && ++count == segments) {
                return path.substring(0, i);
            }
        }
        return path;
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
