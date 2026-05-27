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
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.HttpMethodMapping;
import io.micronaut.jaxrs.container.JaxRsResourceTemplate;
import io.micronaut.web.router.MethodBasedRouteMatch;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.UriRouteMatch;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
    private final String basePath;
    private final String applicationPath;

    /**
     * Construct from an HTTP request.
     *
     * @param request  The HTTP request to this URI
     * @param basePath The base path
     */
    public UriInfoImpl(@NonNull HttpRequest<?> request, @Nullable String basePath) {
        this(request, basePath, null);
    }

    /**
     * Construct from an HTTP request.
     *
     * @param request         The HTTP request to this URI
     * @param basePath        The base path
     * @param applicationPath The application path
     */
    public UriInfoImpl(@NonNull HttpRequest<?> request, @Nullable String basePath, @Nullable String applicationPath) {
        this.request = request;
        this.basePath = basePath == null || basePath.equals("/") ? null : basePath;
        this.applicationPath = applicationPath == null || applicationPath.equals("/") ? "" : normalizeAbsolutePath(applicationPath);
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
        return getPath(getRequestPath(decode), decode);
    }

    @Override
    public List<PathSegment> getPathSegments() {
        return getPathSegments(true);
    }

    @Override
    public List<PathSegment> getPathSegments(boolean decode) {
        return Stream.of(getRequestPath(decode).split("/"))
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

    private String getRequestPath(boolean decode) {
        if (decode) {
            return request.getPath();
        }
        String rawPath = request.getUri().getRawPath();
        return rawPath == null ? request.getPath() : rawPath;
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
        URI uri = request.getUri();
        String rawPath = uri.getRawPath();
        return uriWithRawPath(rawPath == null || rawPath.isEmpty() ? "/" : rawPath, null);
    }

    @Override
    public UriBuilder getAbsolutePathBuilder() {
        return UriBuilder.fromUri(getAbsolutePath());
    }

    @Override
    public URI getBaseUri() {
        return uriWithRawPath(baseUriPath(), null);
    }

    @Override
    public UriBuilder getBaseUriBuilder() {
        return UriBuilder.fromUri(getBaseUri());
    }

    private String baseUriPath() {
        if (basePath == null) {
            return "/";
        }
        String path = basePath.startsWith("/") ? basePath : "/" + basePath;
        return path.endsWith("/") ? path : path + "/";
    }

    private URI uriWithRawPath(String rawPath, @Nullable String rawQuery) {
        URI uri = request.getUri();
        StringBuilder builder = new StringBuilder();
        String scheme = uri.getScheme();
        String rawAuthority = uri.getRawAuthority();
        if (scheme != null && rawAuthority != null) {
            builder.append(scheme).append("://").append(rawAuthority);
        } else {
            scheme = request.isSecure() ? HttpRequest.SCHEME_HTTPS : HttpRequest.SCHEME_HTTP;
            String host = request.getServerName();
            int port = uri.getPort();
            InetSocketAddress serverAddress = request.getServerAddress();
            if ((host == null || host.isBlank()) && serverAddress != null) {
                host = serverAddress.getHostString();
            }
            if (port < 0 && serverAddress != null) {
                port = serverAddress.getPort();
            }
            if (host != null && !host.isBlank()) {
                builder.append(scheme).append("://").append(authority(host, port));
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

    private static String authority(String host, int port) {
        String authorityHost = host.indexOf(':') > -1 && !host.startsWith("[") ? '[' + host + ']' : host;
        return port < 0 ? authorityHost : authorityHost + ':' + port;
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
        RouteMatch<?> match = routeMatch();
        if (match instanceof UriRouteMatch<?, ?> uriRouteMatch) {
            String template = match.getAnnotationMetadata()
                .stringValue(JaxRsResourceTemplate.class)
                .orElseGet(() -> stripPathPrefix(uriRouteMatch.getRouteInfo().getUriMatchTemplate().toString(), basePath));
            if (!applicationPath.isEmpty()) {
                template = applicationPath + normalizeAbsolutePath(template);
            }
            return normalizeAbsolutePath(template);
        }
        return "";
    }

    @Override
    public List<String> getMatchedURIs(boolean decode) {
        RouteMatch<?> match = routeMatch();
        String matchedUri = normalizeMatchedUri(matchedUri(match, decode));
        String resourceUri = rootResourceUri(match, matchedUri);
        if (resourceUri.isEmpty() || resourceUri.equals(matchedUri)) {
            return List.of(matchedUri);
        }
        return List.of(matchedUri, resourceUri);
    }

    @Override
    public List<Object> getMatchedResources() {
        RouteMatch<?> match = routeMatch();
        if (match instanceof MethodBasedRouteMatch<?, ?> methodBasedRouteMatch) {
            return List.of(methodBasedRouteMatch.getTarget());
        }
        return List.of();
    }

    private RouteMatch<?> routeMatch() {
        return RouteAttributes.getRouteMatch(request)
            .orElseThrow(() -> new IllegalStateException("Route match not available!"));
    }

    private String matchedUri(RouteMatch<?> match, boolean decode) {
        if (match instanceof UriRouteMatch<?, ?> uriRouteMatch) {
            return getPath(uriRouteMatch.getUri(), decode);
        }
        return getPath(decode);
    }

    private static String rootResourceUri(RouteMatch<?> match, String matchedUri) {
        return match.getAnnotationMetadata()
            .stringValue(HttpMethodMapping.class)
            .map(UriInfoImpl::normalizeMatchedUri)
            .filter(StringUtils::isNotEmpty)
            .filter(methodUri -> matchedUri.endsWith(methodUri))
            .map(methodUri -> normalizeMatchedUri(matchedUri.substring(0, matchedUri.length() - methodUri.length())))
            .orElse("");
    }

    private static String normalizeMatchedUri(String uri) {
        if (uri.startsWith("/")) {
            uri = uri.substring(1);
        }
        if (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    private static String stripPathPrefix(String path, @Nullable String prefix) {
        if (prefix == null || prefix.isEmpty() || !path.startsWith(prefix)) {
            return path;
        }
        int prefixLength = prefix.length();
        if (path.length() == prefixLength || path.charAt(prefixLength) == '/') {
            return path.substring(prefixLength);
        }
        return path;
    }

    private static String normalizeAbsolutePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : '/' + path;
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
