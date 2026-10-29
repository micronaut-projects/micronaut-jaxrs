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

import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriBuilderException;
import org.jspecify.annotations.Nullable;

import java.io.Serial;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
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

    @Nullable
    private String host;
    @Nullable
    private String scheme;
    private int port = -1;

    @Nullable
    private String userInfo;
    @Nullable
    private String path;
    @Nullable
    private String query;
    @Nullable
    private String fragment;
    @Nullable
    private String ssp;
    @Nullable
    private String authority;

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
            // an opaque URI has no path; its fragment is not part of the scheme-specific part
            this.path = null;
            this.scheme = opaque.group(1);
            String schemeSpecificPart = opaque.group(2);
            int hash = schemeSpecificPart.indexOf('#');
            if (hash > -1) {
                this.ssp = schemeSpecificPart.substring(0, hash);
                fragment(schemeSpecificPart.substring(hash + 1));
            } else {
                this.ssp = schemeSpecificPart;
            }
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
        if (ssp.indexOf('{') != -1) {
            // a template, which is not a URI yet: parsed like the template of a URI
            return uriTemplate(sb);
        }
        URI uri = URI.create(sb.toString());

        if (uri.getRawSchemeSpecificPart() != null && uri.getRawPath() == null) {
            // an opaque URI replaces the hierarchical components
            this.ssp = uri.getRawSchemeSpecificPart();
            authority = null;
            userInfo = null;
            host = null;
            port = -1;
            path = null;
            query = null;
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
        this.userInfo = ui == null || ui.isEmpty() ? null : Encode.encodeUserInfo(ui);
        return this;
    }

    @Override
    public UriBuilder host(String host) throws IllegalArgumentException {
        if (host != null && host.isEmpty()) {
            throw new IllegalArgumentException("invalid host");
        }
        this.host = host == null ? null : Encode.encodeHost(host);
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

    private static String paths(@Nullable String basePath, String... segments) {
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
                segment = Encode.encodePath(segment);
                path.append(segment);
            } else {
                segment = Encode.encodePath(segment);
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

        path = paths(path, segment);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public UriBuilder path(Class resource) throws IllegalArgumentException {
        if (resource == null) {
            throw new IllegalArgumentException("resource was null");
        }

        String value = JaxRsReflection.get().annotationMetadata(resource).stringValue(Path.class).orElse(null);
        if (value == null) {
            throw new IllegalArgumentException(JaxRsReflection.get().isReflective()
                ? "class must be annotated with @Path"
                : "class must be annotated with @Path, and the annotations of a class the annotation processors did not see need reflection: add io.micronaut:micronaut-reflection to the runtime classpath");
        }
        path = paths(path, new String[] {value});
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

        // the public methods of the class, of its introspection
        BeanIntrospection<?> introspection = JaxRsReflection.get().introspection(resource);
        String methodPath = null;
        if (introspection != null) {
            for (BeanMethod<?, ?> m : introspection.getBeanMethods()) {
                AnnotationMetadata metadata = m.getAnnotationMetadata();
                AnnotationMetadata own = metadata instanceof AnnotationMetadataHierarchy hierarchy ? hierarchy.getDeclaredMetadata() : metadata;
                String value = own.stringValue(Path.class).orElse(null);
                if (m.getName().equals(method) && value != null) {
                    if (methodPath != null) {
                        throw new IllegalArgumentException("Two methods with the same path " + method);
                    }
                    methodPath = value;
                }
            }
        }
        if (methodPath == null) {
            throw new IllegalArgumentException("No public method annotated with @Path " + resource.getName() + " " + method);
        }
        path = paths(path, methodPath);
        return this;
    }

    @Override
    public UriBuilder path(Method method) throws IllegalArgumentException {
        if (method == null) {
            throw new IllegalArgumentException("method is null");
        }
        String value = JaxRsReflection.get().annotationMetadata(method).stringValue(Path.class).orElse(null);
        if (value == null) {
            throw new IllegalArgumentException("Method not annotated with @Path");
        }
        path = paths(path, value);
        return this;
    }

    @Override
    public UriBuilder replaceMatrix(String matrix) throws IllegalArgumentException {
        if (matrix == null) {
            // removes the matrix parameters, with their semicolon
            matrix = "";
        } else if (!matrix.startsWith(";")) {
            matrix = ";" + matrix;
        }
        matrix = Encode.encodePath(matrix);
        if (path == null) {
            path = matrix.isEmpty() ? null : matrix;
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
            throw new UriBuilderException("Failed to create URI", e);
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
            // the scheme-specific part of an opaque URI may have templates, e.g. mailto:{user}@example.org
            replaceParameter(paramMap, fromEncodedMap, isTemplate, ssp, builder, encodeSlash);
        } else if (userInfo != null || host != null || port != -1) {
            builder.append("//");
            if (userInfo != null) {
                replaceComponentParameter(paramMap, fromEncodedMap, isTemplate, userInfo, builder, Encode.Component.USER_INFO).append("@");
            }
            if (host != null) {
                if (host.isEmpty()) {
                    throw new UriBuilderException("empty host");
                }
                replaceComponentParameter(paramMap, fromEncodedMap, isTemplate, host, builder, Encode.Component.HOST);
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
            replaceParameter(paramMap, fromEncodedMap, isTemplate, path, tmp, encodeSlash);
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
            replaceComponentParameter(paramMap, fromEncodedMap, isTemplate, fragment, builder, Encode.Component.FRAGMENT);
        }
        return builder;
    }

    private static Matcher createUriParamMatcher(String string) {
        return URI_PARAM_PATTERN.matcher(Encode.replaceEnclosedCurlyBracesCS(string));
    }

    private StringBuilder replaceParameter(Map<String, ?> paramMap, boolean fromEncodedMap, boolean isTemplate,
                                           String string, StringBuilder builder, boolean encodeSlash) {
        return replaceParameter(paramMap, fromEncodedMap, isTemplate, string, builder, encodeSlash, null);
    }

    /**
     * Replace the template parameters of a URI component other than the path, encoded for it: e.g.
     * a host value cannot change the authority.
     */
    private StringBuilder replaceComponentParameter(Map<String, ?> paramMap, boolean fromEncodedMap, boolean isTemplate,
                                                    String string, StringBuilder builder, Encode.Component component) {
        return replaceParameter(paramMap, fromEncodedMap, isTemplate, string, builder, false, component);
    }

    private StringBuilder replaceParameter(Map<String, ?> paramMap, boolean fromEncodedMap, boolean isTemplate,
                                           String string, StringBuilder builder, boolean encodeSlash,
                                           Encode.@Nullable Component component) {
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

            if (component != null) {
                stringValue = Encode.encodeComponentValue(stringValue, component, fromEncodedMap);
            } else if (!fromEncodedMap) {
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
            // the names in the path are encoded
            map.remove(Encode.encodeMatrixParam(name));
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

    @Override
    public UriBuilder queryParam(String name, Object... values) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name parameter is null");
        }
        if (values == null) {
            throw new IllegalArgumentException("Values parameter is null");
        }
        if (values.length == 0) {
            // no parameter to add
            return this;
        }

        StringBuilder sb = new StringBuilder();
        String prefix = "";
        if (query == null) {
            query = "";
        } else {
            sb.append(query).append("&");
        }
        String queryParamName = Encode.encodeQueryParam(name);
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException("Value is null");
            }

            sb.append(prefix);
            String queryParamValue = Encode.encodeQueryParam(value.toString());
            prefix = "&";
            sb.append(queryParamName).append("=").append(queryParamValue);
        }

        query = sb.toString();
        return this;
    }

    @Override
    public UriBuilder replaceQueryParam(String name, Object... values) throws IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("Name parameter is null");
        }
        if (query == null || query.isEmpty()) {
            // nothing to remove
            return values == null || values.length == 0 ? this : queryParam(name, values);
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

        return uriTemplate(buildCharSequence(Map.of(name, value), true, true, false));
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

        return uriTemplate(buildCharSequence(templateValues, true, true, false));
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
        public @Nullable Object get(Object key) {
            @Nullable Object object;
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
