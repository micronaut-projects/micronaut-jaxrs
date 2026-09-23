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

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;
import jakarta.inject.Singleton;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.security.Principal;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The bean context implementation of {@link HttpServletRequest}, which a {@code @Context} member of
 * a bean is injected with: the servlet request of the current request, the one of the servlet
 * container or else a stub.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
@Singleton
@Requires(classes = HttpServletRequest.class)
final class JaxRsContextHttpServletRequest implements HttpServletRequest {

    private static final Argument<HttpServletRequest> SERVLET_REQUEST = Argument.of(HttpServletRequest.class);

    private final BeanProvider<JaxRsRouteSupport> routeSupport;

    JaxRsContextHttpServletRequest(BeanProvider<JaxRsRouteSupport> routeSupport) {
        this.routeSupport = routeSupport;
    }

    private HttpServletRequest current() {
        HttpRequest<?> request = ServerRequestContext.currentRequest()
            .orElseThrow(() -> new IllegalStateException("There is no current request"));
        return (HttpServletRequest) Objects.requireNonNull(routeSupport.get().context(request, SERVLET_REQUEST, null));
    }

    @Override
    public Object getAttribute(String name) {
        return current().getAttribute(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return current().getAttributeNames();
    }

    @Override
    public String getCharacterEncoding() {
        return current().getCharacterEncoding();
    }

    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
        current().setCharacterEncoding(encoding);
    }

    @Override
    public void setCharacterEncoding(Charset encoding) {
        current().setCharacterEncoding(encoding);
    }

    @Override
    public int getContentLength() {
        return current().getContentLength();
    }

    @Override
    public long getContentLengthLong() {
        return current().getContentLengthLong();
    }

    @Override
    public String getContentType() {
        return current().getContentType();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return current().getInputStream();
    }

    @Override
    public String getParameter(String name) {
        return current().getParameter(name);
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return current().getParameterNames();
    }

    @Override
    public String[] getParameterValues(String name) {
        return current().getParameterValues(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return current().getParameterMap();
    }

    @Override
    public String getProtocol() {
        return current().getProtocol();
    }

    @Override
    public String getScheme() {
        return current().getScheme();
    }

    @Override
    public String getServerName() {
        return current().getServerName();
    }

    @Override
    public int getServerPort() {
        return current().getServerPort();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return current().getReader();
    }

    @Override
    public String getRemoteAddr() {
        return current().getRemoteAddr();
    }

    @Override
    public String getRemoteHost() {
        return current().getRemoteHost();
    }

    @Override
    public void setAttribute(String name, Object value) {
        current().setAttribute(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        current().removeAttribute(name);
    }

    @Override
    public Locale getLocale() {
        return current().getLocale();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return current().getLocales();
    }

    @Override
    public boolean isSecure() {
        return current().isSecure();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return current().getRequestDispatcher(path);
    }

    @Override
    public int getRemotePort() {
        return current().getRemotePort();
    }

    @Override
    public String getLocalName() {
        return current().getLocalName();
    }

    @Override
    public String getLocalAddr() {
        return current().getLocalAddr();
    }

    @Override
    public int getLocalPort() {
        return current().getLocalPort();
    }

    @Override
    public ServletContext getServletContext() {
        return current().getServletContext();
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        return current().startAsync();
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException {
        return current().startAsync(servletRequest, servletResponse);
    }

    @Override
    public boolean isAsyncStarted() {
        return current().isAsyncStarted();
    }

    @Override
    public boolean isAsyncSupported() {
        return current().isAsyncSupported();
    }

    @Override
    public AsyncContext getAsyncContext() {
        return current().getAsyncContext();
    }

    @Override
    public DispatcherType getDispatcherType() {
        return current().getDispatcherType();
    }

    @Override
    public String getRequestId() {
        return current().getRequestId();
    }

    @Override
    public String getProtocolRequestId() {
        return current().getProtocolRequestId();
    }

    @Override
    public ServletConnection getServletConnection() {
        return current().getServletConnection();
    }

    @Override
    public String getAuthType() {
        return current().getAuthType();
    }

    @Override
    public Cookie[] getCookies() {
        return current().getCookies();
    }

    @Override
    public long getDateHeader(String name) {
        return current().getDateHeader(name);
    }

    @Override
    public String getHeader(String name) {
        return current().getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return current().getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return current().getHeaderNames();
    }

    @Override
    public int getIntHeader(String name) {
        return current().getIntHeader(name);
    }

    @Override
    public HttpServletMapping getHttpServletMapping() {
        return current().getHttpServletMapping();
    }

    @Override
    public String getMethod() {
        return current().getMethod();
    }

    @Override
    public String getPathInfo() {
        return current().getPathInfo();
    }

    @Override
    public String getPathTranslated() {
        return current().getPathTranslated();
    }

    @Override
    public String getContextPath() {
        return current().getContextPath();
    }

    @Override
    public String getQueryString() {
        return current().getQueryString();
    }

    @Override
    public String getRemoteUser() {
        return current().getRemoteUser();
    }

    @Override
    public boolean isUserInRole(String role) {
        return current().isUserInRole(role);
    }

    @Override
    public Principal getUserPrincipal() {
        return current().getUserPrincipal();
    }

    @Override
    public String getRequestedSessionId() {
        return current().getRequestedSessionId();
    }

    @Override
    public String getRequestURI() {
        return current().getRequestURI();
    }

    @Override
    public StringBuffer getRequestURL() {
        return current().getRequestURL();
    }

    @Override
    public String getServletPath() {
        return current().getServletPath();
    }

    @Override
    public HttpSession getSession(boolean create) {
        return current().getSession(create);
    }

    @Override
    public HttpSession getSession() {
        return current().getSession();
    }

    @Override
    public String changeSessionId() {
        return current().changeSessionId();
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return current().isRequestedSessionIdValid();
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return current().isRequestedSessionIdFromCookie();
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return current().isRequestedSessionIdFromURL();
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
        return current().authenticate(response);
    }

    @Override
    public void login(String username, String password) throws ServletException {
        current().login(username, password);
    }

    @Override
    public void logout() throws ServletException {
        current().logout();
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        return current().getParts();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        return current().getPart(name);
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException {
        return current().upgrade(handlerClass);
    }

    @Override
    public Map<String, String> getTrailerFields() {
        return current().getTrailerFields();
    }

    @Override
    public boolean isTrailerFieldsReady() {
        return current().isTrailerFieldsReady();
    }
}
