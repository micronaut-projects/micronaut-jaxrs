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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriBuilderException;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.io.Serial;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@link UriBuilder} implementation.
 * Originally forked from Resteasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @author Denis Stepanov
 * @since 4.7
 */
@Internal
final class JaxRsUriBuilder extends UriBuilder {

    private static final String URI_PARAM_NAME_REGEX = "\\w[\\w.-]*";
    private static final String URI_PARAM_REGEX_REGEX = "[^{}][^{}]*";
    private static final String URI_PARAM_REGEX = "\\{\\s*(" + URI_PARAM_NAME_REGEX + ")\\s*(:\\s*(" + URI_PARAM_REGEX_REGEX
        + "))?}";

    private static final Pattern URI_PARAM_PATTERN = Pattern.compile(URI_PARAM_REGEX);

    private static final Pattern OPAQUE_URI = Pattern.compile("^([^:/?#{]+):([^/].*)");
    private static final Pattern HIERARCHICAL_URI = Pattern
        .compile("^(([^:/?#{]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?");

    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("([^/:]+):(\\d+)");
    private static final Pattern SQUARE_HOST_BRACKETS = Pattern
        .compile("(\\[(([0-9A-Fa-f]{0,4}:){2,7})([0-9A-Fa-f]{0,4})%?.*]):(\\d+)");

    private static final String REPLACEMENT_URI_PARAMETER = "_jaxrs_uri_parameter";
    private static final Pattern PARAM_REPLACEMENT = Pattern.compile(REPLACEMENT_URI_PARAMETER);

    private String host;
    private String scheme;
    private int port = -1;

    private String userInfo;
    private String path;
    private String query;
    private String fragment;
    private String ssp;
    private String authority;
    private boolean encode = true;

    private MultiQueryParamMode queryParamMode = MultiQueryParamMode.MULTI_PAIRS;

    @Override
    public UriBuilder clone() {
        JaxRsUriBuilder impl = new JaxRsUriBuilder();
        impl.host = host;
        impl.scheme = scheme;
        impl.port = port;
        impl.userInfo = userInfo;
        impl.path = path;
        impl.query = query;
        impl.fragment = fragment;
        impl.ssp = ssp;
        impl.authority = authority;
        impl.queryParamMode = queryParamMode;
        impl.encode = encode;

        return impl;
    }

    public static boolean compare(String s1, String s2) {
        if (s1 == s2) {
            return true;
        }
        if (s1 == null || s2 == null) {
            return false;
        }
        return s1.equals(s2);
    }

    public static URI relativize(URI from, URI to) {
        if (!compare(from.getScheme(), to.getScheme())) {
            return to;
        }
        if (!compare(from.getHost(), to.getHost())) {
            return to;
        }
        if (from.getPort() != to.getPort()) {
            return to;
        }
        if (from.getPath() == null && to.getPath() == null) {
            return URI.create("");
        } else if (from.getPath() == null) {
            return URI.create(to.getPath());
        } else if (to.getPath() == null) {
            return to;
        }

        String fromPath = from.getPath();
        if (fromPath.startsWith("/")) {
            fromPath = fromPath.substring(1);
        }
        String[] fsplit = fromPath.split("/");
        String toPath = to.getPath();
        if (toPath.startsWith("/")) {
            toPath = toPath.substring(1);
        }
        String[] tsplit = toPath.split("/");

        int f = 0;

        for (; f < fsplit.length && f < tsplit.length; f++) {
            if (!fsplit[f].equals(tsplit[f])) {
                break;
            }
        }

        UriBuilder builder = UriBuilder.fromPath("");
        for (int i = f; i < fsplit.length; i++) {
            builder.path("..");
        }
        for (int i = f; i < tsplit.length; i++) {
            builder.path(tsplit[i]);
        }
        return builder.build();
    }

    /**
     * You may put path parameters anywhere within the uriTemplate except port.
     *
     * @param uriTemplate uri template
     * @return uri builder
     */
    public static JaxRsUriBuilder fromTemplate(String uriTemplate) {
        JaxRsUriBuilder impl = (JaxRsUriBuilder) RuntimeDelegate.getInstance().createUriBuilder();
        impl.uriTemplate(uriTemplate);
        return impl;
    }

    /**
     * You may put path parameters anywhere within the uriTemplate except port.
     *
     * @param uriTemplate uri template
     * @return uri builder
     */
    public UriBuilder uriTemplate(CharSequence uriTemplate) {
        if (uriTemplate == null) {
            throw new IllegalArgumentException("URI template cannot be null");
        }

        Matcher opaque = OPAQUE_URI.matcher(uriTemplate);
        if (opaque.matches()) {
            this.authority = null;
            this.host = null;
            this.port = -1;
            this.userInfo = null;
            this.query = null;
            this.scheme = opaque.group(1);
            this.ssp = opaque.group(2);
            return this;
        } else {
            Matcher match = HIERARCHICAL_URI.matcher(uriTemplate);
            if (match.matches()) {
                ssp = null;
                return parseHierarchicalUri(uriTemplate, match);
            }
        }
        throw new IllegalArgumentException("Illegal URI template" + uriTemplate);
    }

    private UriBuilder parseHierarchicalUri(CharSequence uriTemplate, Matcher match) {
        boolean scheme = match.group(2) != null;
        if (scheme) {
            this.scheme = match.group(2);
        }
        String authority = match.group(4);
        if (authority != null) {
            this.authority = null;
            String host = match.group(4);
            int at = host.indexOf('@');
            if (at > -1) {
                String user = host.substring(0, at);
                host = host.substring(at + 1);
                this.userInfo = user;
            }

            Matcher hostPortMatch = HOST_PORT_PATTERN.matcher(host);
            if (hostPortMatch.matches()) {
                this.host = hostPortMatch.group(1);
                try {
                    this.port = Integer.parseInt(hostPortMatch.group(2));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Illegal URI template" + uriTemplate, e);
                }
            } else {
                if (host.startsWith("[")) {
                    // Must support an IPv6 hostname of format "[::1]" or [0:0:0:0:0:0:0:0]
                    // and IPv6 link-local format [fe80::1234%1] [ff08::9abc%interface10]
                    Matcher bracketsMatch = SQUARE_HOST_BRACKETS.matcher(host);
                    if (bracketsMatch.matches()) {
                        host = bracketsMatch.group(1);
                        try {
                            this.port = Integer.parseInt(bracketsMatch.group(5));
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Illegal URI template" + uriTemplate, e);
                        }
                    }
                }
                this.host = host;
            }
        }
        if (match.group(5) != null) {
            String group = match.group(5);
            if (!scheme && !"".equals(group) && !group.startsWith("/") && group.indexOf(':') > -1 &&
                group.indexOf('/') > -1 && group.indexOf(':') < group.indexOf('/')) {
                throw new IllegalArgumentException("Illegal URI template" + uriTemplate);
            }
            if (!"".equals(group)) {
                replacePath(group);
            }
        }
        if (match.group(7) != null) {
            replaceQuery(match.group(7));
        }
        if (match.group(9) != null) {
            fragment(match.group(9));
        }
        return this;
    }

    @Override
    public UriBuilder uri(String uriTemplate) throws IllegalArgumentException {
        return uriTemplate(uriTemplate);
    }

    public UriBuilder uriFromCharSequence(CharSequence uriTemplate) throws IllegalArgumentException {
        return uriTemplate(uriTemplate);
    }

    @Override
    public UriBuilder uri(URI uri) throws IllegalArgumentException {
        if (uri == null) {
            throw new IllegalArgumentException("uri cannot be null");
        }

        if (uri.getRawFragment() != null) {
            fragment = uri.getRawFragment();
        }

        if (uri.isOpaque()) {
            scheme = uri.getScheme();
            ssp = uri.getRawSchemeSpecificPart();
            return this;
        }

        if (uri.getScheme() == null) {
            if (ssp != null) {
                if (uri.getRawSchemeSpecificPart() != null) {
                    ssp = uri.getRawSchemeSpecificPart();
                    return this;
                }
            }
        } else {
            scheme = uri.getScheme();
        }

        ssp = null;
        if (uri.getRawAuthority() != null) {
            if (uri.getRawUserInfo() == null && uri.getHost() == null && uri.getPort() == -1) {
                authority = uri.getRawAuthority();
                userInfo = null;
                host = null;
                port = -1;
            } else {
                authority = null;
                if (uri.getRawUserInfo() != null) {
                    userInfo = uri.getRawUserInfo();
                }
                if (uri.getHost() != null) {
                    host = uri.getHost();
                }
                if (uri.getPort() != -1) {
                    port = uri.getPort();
                }
            }
        }

        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            path = uri.getRawPath();
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            query = uri.getRawQuery();
        }

        return this;
    }

    @Override
    public UriBuilder scheme(String scheme) throws IllegalArgumentException {
        this.scheme = scheme;
        return this;
    }

    @Override
    public UriBuilder schemeSpecificPart(String ssp) throws IllegalArgumentException {
        if (ssp == null) {
            throw new IllegalArgumentException("ssp cannot be null");
        }

        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme).append(':');
        }
        sb.append(ssp);
        if (fragment != null && !fragment.isEmpty()) {
            sb.append('#').append(fragment);
        }
        URI uri = URI.create(sb.toString());

        if (uri.getRawSchemeSpecificPart() != null && uri.getRawPath() == null) {
            this.ssp = uri.getRawSchemeSpecificPart();
        } else {
            this.ssp = null;
            userInfo = uri.getRawUserInfo();
            host = uri.getHost();
            port = uri.getPort();
            path = uri.getRawPath();
            query = uri.getRawQuery();

        }
        return this;
    }

    @Override
    public UriBuilder userInfo(String ui) {
        this.userInfo = ui;
        return this;
    }

    @Override
    public UriBuilder host(String host) throws IllegalArgumentException {
        if (host != null && host.isEmpty()) {
            throw new IllegalArgumentException("invalid host");
        }
        this.host = host;
        return this;
    }

    @Override
    public UriBuilder port(int port) throws IllegalArgumentException {
        if (port < -1) {
            throw new IllegalArgumentException("invalid port");
        }
        this.port = port;
        return this;
    }

    public UriBuilder encode(boolean encode) {
        this.encode = encode;
        return this;
    }

    private static String paths(boolean encode, String basePath, String... segments) {
        StringBuilder path = new StringBuilder();
        if (basePath != null) {
            path.append(basePath);
        }
        for (String segment : segments) {
            if ("".equals(segment)) {
                continue;
            }
            if (!path.isEmpty() && path.charAt(path.length() - 1) == '/') {
                if (segment.startsWith("/")) {
                    segment = segment.substring(1);
                    if (segment.isEmpty()) {
                        continue;
                    }
                }
                if (encode) {
                    segment = Encode.encodePath(segment);
                }
                path.append(segment);
            } else {
                if (encode) {
                    segment = Encode.encodePath(segment);
                }
                if (path.isEmpty()) {
                    path.append(segment);
                } else if (segment.startsWith("/")) {
                    path.append(segment);
                } else {
                    path.append("/").append(segment);
                }
            }

        }
        return path.toString();
    }

    @Override
    public UriBuilder path(String segment) throws IllegalArgumentException {
        if (segment == null) {
            throw new IllegalArgumentException("segment was null");
        }

        path = paths(encode, path, segment);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public UriBuilder path(Class resource) throws IllegalArgumentException {
        if (resource == null) {
            throw new IllegalArgumentException("resource was null");
        }

        Path ann = (Path) resource.getAnnotation(Path.class);
        if (ann != null) {
            String[] segments = new String[] {ann.value()};
            path = paths(true, path, segments);
        } else {
            throw new IllegalArgumentException("class must be annotated with @Path");
        }
        return this;
    }

    @Override
    public UriBuilder path(Class resource, String method) throws IllegalArgumentException {
        if (resource == null) {
            throw new IllegalArgumentException("path is null");
        }
        if (method == null) {
            throw new IllegalArgumentException("method is null");
        }

        Method theMethod = null;
        for (Method m : resource.getMethods()) {
            if (m.getName().equals(method)) {
                if (theMethod != null && m.isAnnotationPresent(Path.class)) {
                    throw new IllegalArgumentException("Two methods with the same path " + method);
                }
                if (m.isAnnotationPresent(Path.class)) {
                    theMethod = m;
                }
            }
        }
        if (theMethod == null) {
            throw new IllegalArgumentException("No public method annotated with @Path " + resource.getName() + " " + method);
        }
        return path(theMethod);
    }

    @Override
    public UriBuilder path(Method method) throws IllegalArgumentException {
        if (method == null) {
            throw new IllegalArgumentException("method is null");
        }
        Path ann = method.getAnnotation(Path.class);
        if (ann != null) {
            path = paths(encode, path, ann.value());
        } else {
            throw new IllegalArgumentException("Method not annotated with @Path");
        }
        return this;
    }

    @Override
    public UriBuilder replaceMatrix(String matrix) throws IllegalArgumentException {
        if (matrix == null) {
            matrix = "";
        }
        if (!matrix.startsWith(";")) {
            matrix = ";" + matrix;
        }
        matrix = Encode.encodePath(matrix);
        if (path == null) {
            path = matrix;
        } else {
            int start = path.lastIndexOf('/');
            if (start < 0) {
                start = 0;
            }
            int matrixIndex = path.indexOf(';', start);
            if (matrixIndex > -1) {
                path = path.substring(0, matrixIndex) + matrix;
            } else {
                path += matrix;
            }

        }
        return this;
    }

    @Override
    public UriBuilder replaceQuery(String query) throws IllegalArgumentException {
        if (query == null || query.isEmpty()) {
            this.query = null;
            return this;
        }
        this.query = Encode.encodeQueryString(query);
        return this;
    }

    @Override
    public UriBuilder fragment(String fragment) throws IllegalArgumentException {
        if (fragment == null) {
            this.fragment = null;
            return this;
        }
        this.fragment = Encode.encodeFragment(fragment);
        return this;
    }

    /**
     * Only replace path params in path of URI. This changes state of URIBuilder.
     *
     * @param name      parameter name
     * @param value     parameter value
     * @param isEncoded encoded flag
     * @return uri builder
     */
    public UriBuilder substitutePathParam(String name, Object value, boolean isEncoded) {
        if (path != null) {
            StringBuilder builder = new StringBuilder();
            replacePathParameter(name, value.toString(), isEncoded, path, builder, false);
            path = builder.toString();
        }
        return this;
    }

    @Override
    public URI buildFromMap(Map<String, ?> values) throws IllegalArgumentException, UriBuilderException {
        if (values == null) {
            throw new IllegalArgumentException("Values parameter is null");
        }
        return buildUriFromMap(values, false, true);
    }

    @Override
    public URI buildFromEncodedMap(Map<String, ?> values) throws IllegalArgumentException, UriBuilderException {
        if (values == null) {
            throw new IllegalArgumentException("Values parameter is null");
        }
        return buildUriFromMap(values, true, false);
    }

    @Override
    public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath)
        throws IllegalArgumentException, UriBuilderException {
        if (values == null) {
            throw new IllegalArgumentException("Values parameter is null");
        }
        return buildUriFromMap(values, false, encodeSlashInPath);
    }

    private URI buildUriFromMap(Map<String, ?> paramMap, boolean fromEncodedMap, boolean encodeSlash)
        throws IllegalArgumentException, UriBuilderException {
        String buf = buildString(paramMap, fromEncodedMap, false, encodeSlash);
        try {
            return URI.create(buf);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create URI", e);
        }
    }

    private String buildString(Map<String, ?> paramMap, boolean fromEncodedMap, boolean isTemplate, boolean encodeSlash) {
        return buildCharSequence(paramMap, fromEncodedMap, isTemplate, encodeSlash).toString();
    }

    private CharSequence buildCharSequence(Map<String, ?> paramMap, boolean fromEncodedMap, boolean isTemplate,
                                           boolean encodeSlash) {
        StringBuilder builder = new StringBuilder();

        if (scheme != null) {
            replaceParameter(paramMap, fromEncodedMap, isTemplate, scheme, builder, encodeSlash).append(":");
        }
        if (ssp != null) {
            builder.append(ssp);
        } else if (userInfo != null || host != null || port != -1) {
            builder.append("//");
            if (userInfo != null) {
                replaceParameter(paramMap, fromEncodedMap, isTemplate, userInfo, builder, encodeSlash).append("@");
            }
            if (host != null) {
                if (host.isEmpty()) {
                    throw new UriBuilderException("empty host");
                }
                replaceParameter(paramMap, fromEncodedMap, isTemplate, host, builder, encodeSlash);
            }
            if (port != -1) {
                builder.append(":").append(port);
            }
        } else if (authority != null) {
            builder.append("//");
            replaceParameter(paramMap, fromEncodedMap, isTemplate, authority, builder, encodeSlash);
        }
        if (path != null) {
            StringBuilder tmp = new StringBuilder();
            replaceParameter(paramMap, fromEncodedMap, isTemplate, path, tmp, encode, encodeSlash);
            if (userInfo != null || host != null) {
                if (!tmp.isEmpty() && tmp.charAt(0) != '/') {
                    builder.append("/");
                }
            }
            builder.append(tmp);
        }
        if (query != null) {
            builder.append("?");
            replaceQueryStringParameter(paramMap, fromEncodedMap, isTemplate, query, builder);
        }
        if (fragment != null) {
            builder.append("#");
            replaceParameter(paramMap, fromEncodedMap, isTemplate, fragment, builder, encodeSlash);
        }
        return builder;
    }

    private void replacePathParameter(String name, String value, boolean isEncoded, String string,
                                      StringBuilder builder, boolean encodeSlash) {
        if (string.indexOf('{') == -1) {
            builder.append(string);
            return;
        }
        Matcher matcher = createUriParamMatcher(string);
        int start = 0;
        while (matcher.find()) {
            String param = matcher.group(1);
            if (!param.equals(name)) {
                continue;
            }
            builder.append(string, start, matcher.start());
            if (!isEncoded) {
                if (encodeSlash) {
                    value = Encode.encodePath(value);
                } else {
                    value = Encode.encodePathSegment(value);
                }

            } else {
                value = Encode.encodeNonCodes(value);
            }
            builder.append(value);
            start = matcher.end();
        }
        builder.append(string, start, string.length());
    }

    public static Matcher createUriParamMatcher(String string) {
        return URI_PARAM_PATTERN.matcher(Encode.replaceEnclosedCurlyBracesCS(string));
    }

    private StringBuilder replaceParameter(Map<String, ?> paramMap, boolean fromEncodedMap, boolean isTemplate,
                                           String string, StringBuilder builder, boolean encodeSlash) {
        return replaceParameter(paramMap, fromEncodedMap, isTemplate, string, builder, true, encodeSlash);
    }

    private StringBuilder replaceParameter(Map<String, ?> paramMap, boolean fromEncodedMap, boolean isTemplate,
                                           String string, StringBuilder builder, boolean encode, boolean encodeSlash) {
        if (string.indexOf('{') == -1) {
            return builder.append(string);
        }
        Matcher matcher = createUriParamMatcher(string);
        int start = 0;
        while (matcher.find()) {
            builder.append(string, start, matcher.start());
            String param = matcher.group(1);
            boolean containsValueForParam = paramMap.containsKey(param);
            if (!containsValueForParam) {
                if (isTemplate) {
                    builder.append(matcher.group());
                    start = matcher.end();
                    continue;
                }
                throw new IllegalArgumentException("Path parameter not provided " + param);
            }
            Object value = paramMap.get(param);
            String stringValue = value != null ? value.toString() : null;
            if (stringValue == null) {
                throw new IllegalArgumentException("Template parameter null: " + param);
            }

            if (encode) {
                if (!fromEncodedMap) {
                    if (encodeSlash) {
                        stringValue = Encode.encodePathSegmentAsIs(stringValue);
                    } else {
                        stringValue = Encode.encodePathAsIs(stringValue);
                    }
                } else {
                    if (encodeSlash) {
                        stringValue = Encode.encodePathSegmentSaveEncodings(stringValue);
                    } else {
                        stringValue = Encode.encodePathSaveEncodings(stringValue);
                    }
                }
            }

            builder.append(stringValue);
            start = matcher.end();
        }
        builder.append(string, start, string.length());
        return builder;
    }

    private void replaceQueryStringParameter(Map<String, ?> paramMap, boolean fromEncodedMap,
                                             boolean isTemplate, String string, StringBuilder builder) {
        if (string.indexOf('{') == -1) {
            builder.append(string);
            return;
        }
        Matcher matcher = createUriParamMatcher(string);
        int start = 0;
        while (matcher.find()) {
            builder.append(string, start, matcher.start());
            String param = matcher.group(1);
            boolean containsValueForParam = paramMap.containsKey(param);
            if (!containsValueForParam) {
                if (isTemplate) {
                    builder.append(matcher.group());
                    start = matcher.end();
                    continue;
                }
                throw new IllegalArgumentException("Path parameter not provided " + param);
            }
            Object value = paramMap.get(param);
            String stringValue = value != null ? value.toString() : null;
            if (stringValue != null) {
                if (!fromEncodedMap) {
                    stringValue = Encode.encodeQueryParamAsIs(stringValue);
                } else {
                    stringValue = Encode.encodeQueryParamSaveEncodings(stringValue);
                }
                builder.append(stringValue);
                start = matcher.end();
            } else {
                throw new IllegalArgumentException("Template param was null: " + param);
            }
        }
        builder.append(string, start, string.length());
    }

    /**
     * Return a unique order list of path params.
     *
     * @return list of path parameters
     */
    public List<String> getPathParamNamesInDeclarationOrder() {
        List<String> params = new ArrayList<>();
        HashSet<String> set = new HashSet<>();
        if (scheme != null) {
            addToPathParamList(params, set, scheme);
        }
        if (userInfo != null) {
            addToPathParamList(params, set, userInfo);
        }
        if (host != null) {
            addToPathParamList(params, set, host);
        }
        if (path != null) {
            addToPathParamList(params, set, path);
        }
        if (query != null) {
            addToPathParamList(params, set, query);
        }
        if (fragment != null) {
            addToPathParamList(params, set, fragment);
        }

        return params;
    }

    private void addToPathParamList(List<String> params, HashSet<String> set, String string) {
        Matcher matcher = URI_PARAM_PATTERN.matcher(Encode.replaceEnclosedCurlyBracesCS(string));
        while (matcher.find()) {
            String param = matcher.group(1);
            if (set.contains(param)) {
                continue;
            }
            set.add(param);
            params.add(param);
        }
    }

    @Override
    public URI build(Object... values) throws IllegalArgumentException, UriBuilderException {
        if (values == null) {
            throw new IllegalArgumentException("Values parameter is null");
        }
        return buildFromValues(true, false, values);
    }

    private URI buildFromValues(boolean encodeSlash, boolean encoded, Object... values) {
        try {
            String buf = buildString(new URITemplateParametersMap(values), encoded, false, encodeSlash);
            return new URI(buf);
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            throw new UriBuilderException("failed to create URI", e);
        }
    }

    @Override
    public UriBuilder matrixParam(String name, Object... values) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name parameter is null");
        }
        if (values == null) {
            throw new IllegalArgumentException("Values parameter is null");
        }
        checkForNullValues(values);
        if (path == null) {
            path = "";
        }
        for (Object val : values) {
            path += ";" + Encode.encodeMatrixParam(name) + "=" + Encode.encodeMatrixParam(val.toString());
        }
        return this;
    }

    @Override
    public UriBuilder replaceMatrixParam(String name, Object... values) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name parameter is null");
        }
        if (path == null) {
            if (values != null && values.length > 0) {
                return matrixParam(name, values);
            }
            return this;
        }

        // remove all path param expressions so we don't accidentally start replacing within a regular expression
        ArrayList<String> pathParams = new ArrayList<>();
        boolean foundParam = false;

        CharSequence pathWithoutEnclosedCurlyBraces = Encode.replaceEnclosedCurlyBracesCS(this.path);
        Matcher matcher = Encode.URI_TEMPLATE_PATTERN.matcher(pathWithoutEnclosedCurlyBraces);
        StringBuilder newSegment = new StringBuilder();
        int from = 0;
        while (matcher.find()) {
            newSegment.append(pathWithoutEnclosedCurlyBraces, from, matcher.start());
            foundParam = true;
            String group = matcher.group();
            pathParams.add(Encode.recoverEnclosedCurlyBraces(group));
            newSegment.append(REPLACEMENT_URI_PARAMETER);
            from = matcher.end();
        }
        newSegment.append(pathWithoutEnclosedCurlyBraces, from, pathWithoutEnclosedCurlyBraces.length());
        path = newSegment.toString();

        // Find last path segment
        int start = path.lastIndexOf('/');
        if (start < 0) {
            start = 0;
        }

        int matrixIndex = path.indexOf(';', start);
        if (matrixIndex > -1) {

            String matrixParams = path.substring(matrixIndex + 1);
            path = path.substring(0, matrixIndex);
            MultivaluedMap<String, String> map = new InternalMultivaluedHashMap<>();

            String[] params = matrixParams.split(";");
            for (String param : params) {
                int idx = param.indexOf('=');
                if (idx < 0) {
                    map.add(param, null);
                } else {
                    String theName = param.substring(0, idx);
                    String value = "";
                    if (idx + 1 < param.length()) {
                        value = param.substring(idx + 1);
                    }
                    map.add(theName, value);
                }
            }
            map.remove(name);
            for (String theName : map.keySet()) {
                List<String> vals = map.get(theName);
                for (Object val : vals) {
                    if (val == null) {
                        path += ";" + theName;
                    } else {
                        path += ";" + theName + "=" + val;
                    }
                }
            }
        }
        if (values != null && values.length > 0) {
            matrixParam(name, values);
        }

        // put back all path param expressions
        if (foundParam) {
            matcher = PARAM_REPLACEMENT.matcher(path);
            newSegment = new StringBuilder();
            int i = 0;
            from = 0;
            while (matcher.find()) {
                newSegment.append(this.path, from, matcher.start());
                newSegment.append(pathParams.get(i++));
                from = matcher.end();
            }
            newSegment.append(this.path, from, this.path.length());
            path = newSegment.toString();
        }
        return this;
    }

    /**
     * Called by ClientRequest.getUri() to add a query parameter for {@code @QueryParam} parameters.
     * We do not use UriBuilder.queryParam() because
     * <ul>
     * <li>queryParam() supports URI template processing and this method must
     * always encode braces (for parameter substitution is not possible for
     * {@code @QueryParam} parameters).
     * <li>queryParam() supports "contextual URI encoding" (i.e., it does not
     * encode {@code %} characters that are followed by two hex characters).
     * The JavaDoc for {@code @QueryParam.value()} explicitly states that
     * the value is specified in decoded format and that "any percent
     * encoded literals within the value will not be decoded and will
     * instead be treated as literal text". This means that it is an
     * explicit bug to perform contextual URI encoding of this method's
     * name parameter; hence, we must always encode said parameter. This
     * method also foregoes contextual URI encoding on this method's values
     * parameter because it represents arbitrary data passed to a
     * {@code QueryParam} parameter of a client proxy (since the client
     * proxy is nothing more than a transport layer, it should not be
     * "interpreting" such data; instead, it should faithfully transmit
     * this data over the wire).
     * </ul>
     *
     * @param name   the name of the query parameter.
     * @param values the value(s) of the query parameter.
     * @return Returns this instance to allow call chaining.
     */
    public UriBuilder clientQueryParam(String name, Object... values) throws IllegalArgumentException {
        StringBuilder sb = new StringBuilder();
        String prefix = "";
        if (query == null) {
            query = "";
        } else {
            sb.append(query).append("&");
        }

        if (name == null) {
            throw new IllegalArgumentException("Name parameter is null");
        }
        if (values == null) {
            throw new IllegalArgumentException("Values parameter is null");
        }

        String queryParamName = encode ? Encode.encodeQueryParamAsIs(name) : name;
        if (queryParamMode == MultiQueryParamMode.COMMA_SEPARATED) {
            sb.append(queryParamName).append("=");
        }
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException("Value is null");
            }

            sb.append(prefix);
            String queryParamValue = encode ? Encode.encodeQueryParamAsIs(value.toString()) : value.toString();
            switch (queryParamMode) {
                case MULTI_PAIRS -> {
                    prefix = "&";
                    sb.append(queryParamName).append("=").append(queryParamValue);
                }
                case COMMA_SEPARATED -> {
                    prefix = ",";
                    sb.append(queryParamValue);
                }
                case ARRAY_PAIRS -> {
                    prefix = "&";
                    String queryParamConnector = arrayPairsConnector(values);
                    sb.append(queryParamName).append(queryParamConnector).append(queryParamValue);
                }
                default -> throw new IllegalStateException("Unexpected value: " + queryParamMode);
            }
        }

        query = sb.toString();
        return this;
    }

    @Override
    public UriBuilder queryParam(String name, Object... values) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name parameter is null");
        }

        StringBuilder sb = new StringBuilder();
        String prefix = "";
        if (query == null) {
            query = "";
        } else {
            sb.append(query).append("&");
        }
        if (values != null) {
            String queryParamName = encode ? Encode.encodeQueryParam(name) : name;
            if (queryParamMode == MultiQueryParamMode.COMMA_SEPARATED) {
                sb.append(queryParamName).append("=");
            }
            for (Object value : values) {
                if (value == null) {
                    throw new IllegalArgumentException("Value is null");
                }

                sb.append(prefix);
                String queryParamValue = encode ? Encode.encodeQueryParam(value.toString()) : value.toString();
                switch (queryParamMode) {
                    case MULTI_PAIRS -> {
                        prefix = "&";
                        sb.append(queryParamName).append("=").append(queryParamValue);
                    }
                    case COMMA_SEPARATED -> {
                        prefix = ",";
                        sb.append(queryParamValue);
                    }
                    case ARRAY_PAIRS -> {
                        prefix = "&";
                        String queryParamConnector = arrayPairsConnector(values);
                        sb.append(queryParamName).append(queryParamConnector).append(queryParamValue);
                    }
                    default -> throw new IllegalStateException("Unexpected value: " + queryParamMode);
                }
            }
        }

        query = sb.toString();
        return this;
    }

    private String arrayPairsConnector(Object[] values) {
        return values.length == 1 ? "=" : "[]=";
    }

    @Override
    public UriBuilder replaceQueryParam(String name, Object... values) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name parameter is null");
        }
        if (query == null || query.isEmpty()) {
            return queryParam(name, values);
        }

        String[] params = query.split("&");
        query = null;

        String replacedName = Encode.encodeQueryParam(name);

        for (String param : params) {
            int pos = param.indexOf('=');
            if (pos >= 0) {
                String paramName = param.substring(0, pos);
                if (paramName.equals(replacedName)) {
                    continue;
                }
            } else {
                if (param.equals(replacedName)) {
                    continue;
                }
            }
            if (query == null) {
                query = "";
            } else {
                query += "&";
            }
            query += param;
        }
        // don't set values if values is null
        if (values == null || values.length == 0) {
            return this;
        }
        return queryParam(name, values);
    }

    public String getHost() {
        return host;
    }

    public String getScheme() {
        return scheme;
    }

    public int getPort() {
        return port;
    }

    public String getUserInfo() {
        return userInfo;
    }

    public String getPath() {
        return path;
    }

    public String getQuery() {
        return query;
    }

    public String getFragment() {
        return fragment;
    }

    @Override
    public UriBuilder segment(String... segments) throws IllegalArgumentException {
        if (segments == null) {
            throw new IllegalArgumentException("Segments parameter is null");
        }
        for (String segment : segments) {
            if (segment == null) {
                throw new IllegalArgumentException("Segment is null");
            }
            path(Encode.encodePathSegment(segment));
        }
        return this;
    }

    @Override
    public URI buildFromEncoded(Object... values) throws IllegalArgumentException, UriBuilderException {
        if (values == null) {
            throw new IllegalArgumentException("Values parameter is null");
        }

        return buildFromValues(false, true, values);
    }

    @Override
    public UriBuilder replacePath(String path) {
        if (path == null) {
            this.path = null;
            return this;
        }
        this.path = Encode.encodePath(path);
        return this;
    }

    @Override
    public URI build(Object[] values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        if (values == null) {
            throw new IllegalArgumentException("Values parameter is null");
        }

        return buildFromValues(encodeSlashInPath, false, values);
    }

    @Override
    public String toTemplate() {
        return buildString(Map.of(), true, true, true);
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name parameter is null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value parameter is null");
        }
        return resolveTemplates(Map.of(name, value));
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues) throws IllegalArgumentException {
        if (templateValues == null) {
            throw new IllegalArgumentException("TemplateValues parameter is null");
        }
        checkForNullKeysOrValues(templateValues);

        return uriTemplate(buildCharSequence(templateValues, false, true, true));
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name parameter is null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value parameter is null");
        }

        return uriTemplate(buildCharSequence(Map.of(name, value), false, true, encodeSlashInPath));
    }

    @Override
    public UriBuilder resolveTemplateFromEncoded(String name, Object value) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name parameter is null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value parameter is null");
        }

        return uriTemplate(buildCharSequence(Map.of(name, value), true, true, true));
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) throws IllegalArgumentException {
        if (templateValues == null) {
            throw new IllegalArgumentException("TemplateValues parameter is null");
        }
        checkForNullKeysOrValues(templateValues);

        return uriTemplate(buildCharSequence(templateValues, false, true, encodeSlashInPath));
    }

    @Override
    public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> templateValues) throws IllegalArgumentException {
        if (templateValues == null) {
            throw new IllegalArgumentException("TemplateValues parameter is null");
        }
        checkForNullKeysOrValues(templateValues);

        return uriTemplate(buildCharSequence(templateValues, true, true, true));
    }

    private void checkForNullKeysOrValues(Map<?, ?> map) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null) {
                throw new IllegalArgumentException("map key null");
            }
            if (e.getValue() == null) {
                throw new IllegalArgumentException("map value null");
            }
        }
    }

    private void checkForNullValues(Object[] values) {
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException("array value null");
            }
        }
    }

    private enum MultiQueryParamMode {
        /*
         * <code>foo=v1&amp;foo=v2&amp;foo=v3</code>
         */
        MULTI_PAIRS,
        /*
         * <code>foo=v1,v2,v3</code>
         */
        COMMA_SEPARATED,
        /*
         * <code>foo[]=v1&amp;foo[]=v2&amp;foo[]=v3</code>
         */
        ARRAY_PAIRS
    }

    /**
     * Without the bug in put/putAll that leaks external mutable storage into our storage.
     *
     * @param <Key>   Key
     * @param <Value> Value
     */
    private static final class InternalMultivaluedHashMap<Key, Value> extends MultivaluedHashMap<Key, Value>
        implements MultivaluedMap<Key, Value> {

        @Serial
        private static final long serialVersionUID = 4136263572124588039L;

        @Override
        public List<Value> put(Key key, List<Value> value) {
            if (value != null) {
                // this is the storage the supertype uses
                value = new LinkedList<>(value);
            }
            return super.put(key, value);
        }

        @Override
        public void putAll(Map<? extends Key, ? extends List<Value>> m) {
            for (Entry<? extends Key, ? extends List<Value>> entry : m.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static final class URITemplateParametersMap extends HashMap<String, Object> {

        private final Object[] parameterValues;
        private int index;

        private URITemplateParametersMap(final Object... parameterValues) {
            this.parameterValues = parameterValues;
        }

        @Override
        public Object get(Object key) {
            Object object;
            if (!super.containsKey(key) && this.index != this.parameterValues.length) {
                object = this.parameterValues[this.index++];
                super.put((String) key, object);
            } else {
                object = super.get(key);
            }
            return object;
        }

        @Override
        public boolean containsKey(Object key) {
            boolean containsKey = super.containsKey(key);
            if (!containsKey && this.index != this.parameterValues.length) {
                super.put((String) key, this.parameterValues[this.index++]);
                containsKey = true;
            }
            return containsKey;
        }

    }
}
