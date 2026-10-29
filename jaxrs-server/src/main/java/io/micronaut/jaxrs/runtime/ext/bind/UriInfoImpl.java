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
import io.micronaut.web.router.UriRouteMatch;
import jakarta.ws.rs.core.AbstractMultivaluedMap;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * The attribute of a request whose pre-matching filters run: no resource is matched yet.
     */
    public static final String PRE_MATCHING = UriInfoImpl.class.getName() + ".preMatching";

    /**
     * The variable of the router for the rest of the path a sub-resource locator did not match.
     */
    private static final String LOCATED_PATH = "micronaut_located_path";

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

    /**
     * @return The path of the request relative to the base URI, as it appears in the request, not decoded
     */
    private String relativeRawPath() {
        String path = request.getPath();
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
        // relative to the base URI, which ends with a slash
        String path = relativeRawPath();
        path = path.startsWith("/") ? path.substring(1) : path;
        return decode ? decodePath(path) : path;
    }

    /**
     * Percent-decode a part of a path: a plus is not a space in a path.
     */
    private static String decodePath(String raw) {
        if (raw.indexOf('%') < 0) {
            return raw;
        }
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // not well encoded: as it is
            return raw;
        }
    }

    @Override
    public List<PathSegment> getPathSegments() {
        return getPathSegments(true);
    }

    @Override
    public List<PathSegment> getPathSegments(boolean decode) {
        // relative to the base URI, split before decoding: an encoded slash or semicolon is not a separator
        return Stream.of(relativeRawPath().split("/"))
            .filter(StringUtils::isNotEmpty)
            .<PathSegment>map(token -> {
                String[] segmentTokens = token.split(";");
                MultivaluedMap<String, String> params = new MultiMapNullPermitted<>();
                for (int i = 1; i < segmentTokens.length; ++i) {
                    String[] keyVal = segmentTokens[i].split("=", 2);
                    String key = keyVal[0];
                    String val = keyVal.length > 1 ? keyVal[1] : null;
                    params.add(decode ? decodePath(key) : key, val == null || !decode ? val : decodePath(val));
                }
                return new UriPathSegment(decode ? decodePath(segmentTokens[0]) : segmentTokens[0], unmodifiable(params));
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
        } else if (match instanceof UriRouteMatch<?, ?> uriMatch) {
            // the values as they appear in the path
            uriMatch.getRawVariableValues().forEach((name, value) -> map.add(variableName(name), value.toString()));
        }
        addLocatorVariables(map, decode);
        return unmodifiable(map);
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters() {
        return getQueryParameters(true);
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
        var map = new MultivaluedHashMap<String, String>();
        JaxRsRequestUri.of(request).queryParameters(decode).forEach((name, values) -> values.forEach(value -> map.add(name, value)));
        return unmodifiable(map);
    }

    /**
     * The query parameters of a URI: only {@code &} separates them, a semicolon is a part of a value. A parameter
     * without {@code =} has an empty value, and one with an empty name is ignored.
     *
     * @param uri    The URI
     * @param decode Whether the names and values are decoded, a plus being a space
     * @return The values of each parameter, in the order of the query
     */
    public static Map<String, List<String>> queryParameters(URI uri, boolean decode) {
        var map = new LinkedHashMap<String, List<String>>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (name.isEmpty()) {
                continue;
            }
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            map.computeIfAbsent(decode ? decodeQuery(name) : name, k -> new ArrayList<>(1))
                .add(decode ? decodeQuery(value) : value);
        }
        return map;
    }

    /**
     * Decode a part of a query, a plus being a space.
     *
     * @param raw The name or value as it appears in the query
     * @return The decoded name or value, or as it is when it is not well encoded
     */
    public static String decodeQuery(String raw) {
        if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) {
            return raw;
        }
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // not well encoded: as it is
            return raw;
        }
    }

    @Override
    public List<String> getMatchedURIs() {
        return getMatchedURIs(true);
    }

    @Override
    public String getMatchedResourceTemplate() {
        // the template of the matched route, under the prefixes of the locators that located it,
        // relative to the context path of the server
        return io.micronaut.web.router.RouteAttributes.getRouteMatch(request)
            .filter(io.micronaut.web.router.UriRouteMatch.class::isInstance)
            .map(match -> io.micronaut.jaxrs.container.JaxRsRouteTemplateEngine.withoutMarks(
                io.micronaut.web.router.RouteLocator.uriTemplate((io.micronaut.web.router.UriRouteMatch<?, ?>) match)))
            .or(() -> io.micronaut.web.router.RouteAttributes.getRouteInfo(request)
                .filter(io.micronaut.web.router.UriRouteInfo.class::isInstance)
                .map(route -> io.micronaut.jaxrs.container.JaxRsRouteTemplateEngine.withoutMarks(
                    ((io.micronaut.web.router.UriRouteInfo<?, ?>) route).getRouteTemplate().expression())))
            .map(template -> !contextPath.isEmpty() && template.startsWith(contextPath) ? template.substring(contextPath.length()) : template)
            .orElse("");
    }

    @Override
    public List<String> getMatchedURIs(boolean decode) {
        if (request.getAttribute(PRE_MATCHING).isPresent()) {
            // nothing is matched before the matching
            return List.of();
        }
        // the prefixes are taken from the encoded path, a decoded slash is not a separator
        String path = trimSlashes(getPath(false));
        List<String> uris = new ArrayList<>();
        uris.add(decode ? decodePath(path) : path);
        JaxRsMatched matched = JaxRsMatched.get(request);
        if (matched != null) {
            for (int segments : matched.segments()) {
                if (segments < 0) {
                    // a target located at runtime: its prefix is not known
                    continue;
                }
                String uri = firstSegments(path, segments);
                uri = decode ? decodePath(uri) : uri;
                if (!uri.equals(uris.get(uris.size() - 1))) {
                    uris.add(uri);
                }
            }
        }
        return Collections.unmodifiableList(uris);
    }

    @Override
    public List<Object> getMatchedResources() {
        JaxRsMatched matched = JaxRsMatched.get(request);
        return matched == null ? List.of() : Collections.unmodifiableList(matched.resources());
    }

    @Override
    public URI resolve(URI uri) {
        // against the base URI of the application
        return getBaseUri().resolve(uri);
    }

    @Override
    public URI relativize(URI uri) {
        // a relative URI is resolved against the base URI first, then relativized to the request URI:
        // relative to its last segment, as a reference in it is
        URI resolved = uri.isAbsolute() ? uri : resolve(uri);
        String rawPath = getAbsolutePath().getRawPath();
        URI directory = uriWithRawPath(rawPath.substring(0, rawPath.lastIndexOf('/') + 1), null);
        return directory.relativize(resolved);
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
    /**
     * Add the values of the variables the sub-resource locators matched that a later match
     * replaced, e.g. the {@code id} of a locator {@code {id}/sub} of a located route {@code {id}}:
     * the later values first.
     */
    private void addLocatorVariables(MultivaluedMap<String, String> map, boolean decode) {
        io.micronaut.jaxrs.container.JaxRsMatched matched = io.micronaut.jaxrs.container.JaxRsMatched.get(request);
        if (matched == null || matched.locatorVariables().isEmpty()) {
            return;
        }
        List<io.micronaut.http.PathVariables> stages = new ArrayList<>(matched.locatorVariables());
        Collections.reverse(stages);
        for (io.micronaut.http.PathVariables stage : stages) {
            io.micronaut.http.PathVariables variables = decode ? stage : stage.raw();
            for (String name : variables.names()) {
                if (name.startsWith(LOCATED_PATH)) {
                    continue;
                }
                String value = variables.findString(name).orElse(null);
                List<String> values = map.get(variableName(name));
                if (value != null && (values == null || !values.contains(value))) {
                    map.add(variableName(name), value);
                }
            }
        }
    }

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

    /**
     * A read-only view of a map, as {@link UriInfo} requires for the maps it returns.
     */
    private static <K, V> MultivaluedMap<K, V> unmodifiable(MultivaluedMap<K, V> map) {
        Map<K, List<V>> store = new LinkedHashMap<>();
        map.forEach((key, values) -> store.put(key, Collections.unmodifiableList(values)));
        return new ReadOnlyMultivaluedMap<>(Collections.unmodifiableMap(store));
    }

    /**
     * A multivalued map over an unmodifiable store of unmodifiable lists.
     *
     * @param <K> The key type
     * @param <V> The value type
     */
    private static final class ReadOnlyMultivaluedMap<K, V> extends AbstractMultivaluedMap<K, V> {
        ReadOnlyMultivaluedMap(Map<K, List<V>> store) {
            super(store);
        }
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
