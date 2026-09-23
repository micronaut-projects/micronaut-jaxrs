/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.jaxrs.container;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.Principal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@link HttpServletRequest} of a request served by a server that is not a servlet container:
 * a read-mostly view of the Micronaut request. Its attributes are the properties of the request
 * the filter and interceptor contexts share, like a servlet container synchronizes them. There is
 * no servlet context, session, dispatcher or asynchronous processing, and the entity is read by
 * JAX-RS.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsStubHttpServletRequest implements HttpServletRequest {

    private static final AtomicLong REQUEST_IDS = new AtomicLong();

    private final HttpRequest<?> request;
    private final String contextPath;
    private final Map<String, Object> attributes;
    private final String requestId = Long.toString(REQUEST_IDS.incrementAndGet());
    private @Nullable String characterEncoding;

    /**
     * @param request     The request
     * @param contextPath The context path of the server
     */
    JaxRsStubHttpServletRequest(HttpRequest<?> request, String contextPath) {
        this.request = request;
        this.contextPath = contextPath.equals("/") ? "" : contextPath;
        this.attributes = JaxRsContainerRequestContext.properties(request);
    }

    @Override
    public @Nullable Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(new ArrayList<>(attributes.keySet()));
    }

    @Override
    public void setAttribute(String name, @Nullable Object o) {
        if (o == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, o);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    @Override
    public @Nullable String getCharacterEncoding() {
        if (characterEncoding != null) {
            return characterEncoding;
        }
        return request.getContentType().flatMap(MediaType::getCharset).map(Charset::name).orElse(null);
    }

    @Override
    public void setCharacterEncoding(String env) {
        characterEncoding = env;
    }

    @Override
    public int getContentLength() {
        long length = request.getContentLength();
        return length > Integer.MAX_VALUE ? -1 : (int) length;
    }

    @Override
    public long getContentLengthLong() {
        return request.getContentLength();
    }

    @Override
    public @Nullable String getContentType() {
        return request.getHeaders().get(HttpHeaders.CONTENT_TYPE);
    }

    @Override
    public ServletInputStream getInputStream() {
        throw new UnsupportedOperationException("The entity of the request is read by JAX-RS");
    }

    @Override
    public BufferedReader getReader() {
        throw new UnsupportedOperationException("The entity of the request is read by JAX-RS");
    }

    @Override
    public @Nullable String getParameter(String name) {
        return request.getParameters().get(name);
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(request.getParameters().names());
    }

    @Override
    public String @Nullable [] getParameterValues(String name) {
        List<String> values = request.getParameters().getAll(name);
        return values.isEmpty() ? null : values.toArray(new String[0]);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> parameters = new LinkedHashMap<>();
        for (String name : request.getParameters().names()) {
            parameters.put(name, request.getParameters().getAll(name).toArray(new String[0]));
        }
        return Collections.unmodifiableMap(parameters);
    }

    @Override
    public String getProtocol() {
        return switch (request.getHttpVersion()) {
            case HTTP_1_0 -> "HTTP/1.0";
            case HTTP_1_1 -> "HTTP/1.1";
            case HTTP_2_0 -> "HTTP/2.0";
        };
    }

    @Override
    public String getScheme() {
        return request.isSecure() ? "https" : "http";
    }

    @Override
    public String getServerName() {
        String host = request.getHeaders().get(HttpHeaders.HOST);
        if (host != null && !host.isEmpty()) {
            // a host name, an IPv4 address or an [IPv6] address, with an optional port
            int colon = host.lastIndexOf(':');
            return colon > host.lastIndexOf(']') ? host.substring(0, colon) : host;
        }
        return request.getServerAddress().getHostString();
    }

    @Override
    public int getServerPort() {
        String host = request.getHeaders().get(HttpHeaders.HOST);
        if (host != null) {
            int colon = host.lastIndexOf(':');
            if (colon > host.lastIndexOf(']')) {
                try {
                    return Integer.parseInt(host.substring(colon + 1));
                } catch (NumberFormatException e) {
                    // the port of the connection
                }
            } else if (!host.isEmpty()) {
                return request.isSecure() ? 443 : 80;
            }
        }
        return request.getServerAddress().getPort();
    }

    @Override
    public @Nullable String getRemoteAddr() {
        return address(request.getRemoteAddress());
    }

    @Override
    public String getRemoteHost() {
        return request.getRemoteAddress().getHostString();
    }

    @Override
    public int getRemotePort() {
        return request.getRemoteAddress().getPort();
    }

    @Override
    public String getLocalName() {
        return request.getServerAddress().getHostString();
    }

    @Override
    public @Nullable String getLocalAddr() {
        return address(request.getServerAddress());
    }

    @Override
    public int getLocalPort() {
        return request.getServerAddress().getPort();
    }

    private static String address(InetSocketAddress address) {
        return address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
    }

    @Override
    public Locale getLocale() {
        return request.getLocale().orElse(Locale.getDefault());
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return Collections.enumeration(List.of(getLocale()));
    }

    @Override
    public boolean isSecure() {
        return request.isSecure();
    }

    @Override
    public @Nullable RequestDispatcher getRequestDispatcher(String path) {
        return null;
    }

    @Override
    public ServletContext getServletContext() {
        throw new UnsupportedOperationException("There is no servlet context: the server is not a servlet container");
    }

    @Override
    public AsyncContext startAsync() {
        throw new IllegalStateException("The request does not support asynchronous operations");
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        throw new IllegalStateException("The request does not support asynchronous operations");
    }

    @Override
    public boolean isAsyncStarted() {
        return false;
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw new IllegalStateException("The request is not in asynchronous mode");
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    @Override
    public String getRequestId() {
        return requestId;
    }

    @Override
    public String getProtocolRequestId() {
        return "";
    }

    @Override
    public ServletConnection getServletConnection() {
        throw new UnsupportedOperationException("There is no servlet connection: the server is not a servlet container");
    }

    @Override
    public @Nullable String getAuthType() {
        return null;
    }

    @Override
    public Cookie @Nullable [] getCookies() {
        Collection<io.micronaut.http.cookie.Cookie> all = request.getCookies().getAll();
        if (all.isEmpty()) {
            return null;
        }
        List<Cookie> cookies = new ArrayList<>(all.size());
        for (io.micronaut.http.cookie.Cookie cookie : all) {
            cookies.add(new Cookie(cookie.getName(), cookie.getValue()));
        }
        return cookies.toArray(new Cookie[0]);
    }

    @Override
    public long getDateHeader(String name) {
        String value = request.getHeaders().get(name);
        if (value == null) {
            return -1;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The header " + name + " is not a date: " + value, e);
        }
    }

    @Override
    public @Nullable String getHeader(String name) {
        return request.getHeaders().get(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(request.getHeaders().getAll(name));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(request.getHeaders().names());
    }

    @Override
    public int getIntHeader(String name) {
        String value = request.getHeaders().get(name);
        return value == null ? -1 : Integer.parseInt(value.trim());
    }

    @Override
    public String getMethod() {
        return request.getMethodName();
    }

    @Override
    public @Nullable String getPathInfo() {
        String path = request.getUri().getPath();
        if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.isEmpty() ? null : path;
    }

    @Override
    public @Nullable String getPathTranslated() {
        return null;
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public @Nullable String getQueryString() {
        return request.getUri().getRawQuery();
    }

    @Override
    public @Nullable String getRemoteUser() {
        Principal principal = getUserPrincipal();
        return principal == null ? null : principal.getName();
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public @Nullable Principal getUserPrincipal() {
        return request.getUserPrincipal().orElse(null);
    }

    @Override
    public @Nullable String getRequestedSessionId() {
        return null;
    }

    @Override
    public String getRequestURI() {
        return request.getPath();
    }

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer url = new StringBuffer();
        URI uri = request.getUri();
        if (uri.isAbsolute()) {
            url.append(uri.getScheme()).append("://").append(uri.getRawAuthority());
        } else {
            String host = request.getHeaders().get(HttpHeaders.HOST);
            url.append(getScheme()).append("://").append(host == null ? getServerName() + ':' + getServerPort() : host);
        }
        return url.append(request.getPath());
    }

    @Override
    public String getServletPath() {
        return "";
    }

    @Override
    public @Nullable HttpSession getSession(boolean create) {
        if (create) {
            throw new UnsupportedOperationException("There are no sessions: the server is not a servlet container");
        }
        return null;
    }

    @Override
    public HttpSession getSession() {
        throw new UnsupportedOperationException("There are no sessions: the server is not a servlet container");
    }

    @Override
    public String changeSessionId() {
        throw new IllegalStateException("The request has no session");
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    @Override
    public boolean authenticate(HttpServletResponse response) {
        throw new UnsupportedOperationException("The server is not a servlet container");
    }

    @Override
    public void login(String username, String password) {
        throw new UnsupportedOperationException("The server is not a servlet container");
    }

    @Override
    public void logout() {
        throw new UnsupportedOperationException("The server is not a servlet container");
    }

    @Override
    public Collection<Part> getParts() {
        throw new UnsupportedOperationException("The entity of the request is read by JAX-RS");
    }

    @Override
    public Part getPart(String name) {
        throw new UnsupportedOperationException("The entity of the request is read by JAX-RS");
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
        throw new UnsupportedOperationException("The server is not a servlet container");
    }
}
