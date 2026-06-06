/*
 * Copyright 2017-2024 original authors
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
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.CloseableAvailableByteBody;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.jaxrs.common.JaxRsHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableHeadersMultivaluedMap;
import io.micronaut.jaxrs.common.JaxRsMutableHttpHeaders;
import io.micronaut.jaxrs.runtime.ext.bind.SimpleSecurityContextBinder;
import io.micronaut.jaxrs.runtime.ext.bind.UriInfoImpl;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The implementation of {@link ContainerRequestContext}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsContainerRequestContext implements ContainerRequestContext {

    static final String REQUEST_URI_ATTRIBUTE = JaxRsContainerRequestContext.class.getName() + ".requestUri";
    static final String REQUEST_METHOD_ATTRIBUTE = JaxRsContainerRequestContext.class.getName() + ".requestMethod";

    private final MutableHttpRequest<?> mutableHttpRequest;
    private final ServerHttpRequest<?> serverHttpRequest;
    private final JaxRsHttpHeaders jaxRsHttpHeaders;
    private Response response;
    private final ApplicationProvider applicationProvider;
    private boolean finished;
    private final boolean preMatching;
    private InputStream entityStream;
    private SecurityContext securityContext;

    JaxRsContainerRequestContext(MutableHttpRequest<?> mutableHttpRequest, ApplicationProvider applicationProvider) {
        this(mutableHttpRequest, applicationProvider, false);
    }

    JaxRsContainerRequestContext(MutableHttpRequest<?> mutableHttpRequest,
                                 ApplicationProvider applicationProvider,
                                 boolean preMatching) {
        this.mutableHttpRequest = mutableHttpRequest;
        this.applicationProvider = applicationProvider;
        this.preMatching = preMatching;
        this.serverHttpRequest = ServerRequestContext.currentRequest()
            .filter(ServerHttpRequest.class::isInstance)
            .map(ServerHttpRequest.class::cast)
            .orElse(null);
        this.jaxRsHttpHeaders = JaxRsMutableHttpHeaders.forRequest(mutableHttpRequest.getHeaders());
    }

    @Override
    public Object getProperty(String name) {
        return mutableHttpRequest.getAttributes().getValue(name);
    }

    @Override
    public boolean hasProperty(String name) {
        return mutableHttpRequest.getAttributes().contains(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(mutableHttpRequest.getAttributes().names()));
    }

    @Override
    public void setProperty(String name, Object object) {
        if (object == null) {
            removeProperty(name);
        } else {
            mutableHttpRequest.getAttributes().put(name, object);
        }
    }

    @Override
    public void removeProperty(String name) {
        mutableHttpRequest.getAttributes().remove(name);
    }

    @Override
    public UriInfo getUriInfo() {
        return new UriInfoImpl(mutableHttpRequest, applicationProvider.getPath(), applicationProvider.getApplicationPath());
    }

    @Override
    public void setRequestUri(URI requestUri) {
        checkIsRequestPreMatchingInProgress();
        setMutableRequestUri(requestUri);
    }

    @Override
    public void setRequestUri(URI baseUri, URI requestUri) {
        checkIsRequestPreMatchingInProgress();
        setMutableRequestUri(requestUri.isAbsolute() ? requestUri : baseUri.resolve(requestUri));
    }

    @Override
    public Request getRequest() {
        return new JaxRsContextRequest(mutableHttpRequest);
    }

    @Override
    public String getMethod() {
        return mutableHttpRequest.getAttribute(REQUEST_METHOD_ATTRIBUTE, String.class)
            .orElseGet(mutableHttpRequest::getMethodName);
    }

    @Override
    public void setMethod(String method) {
        checkIsRequestPreMatchingInProgress();
        mutableHttpRequest.setAttribute(REQUEST_METHOD_ATTRIBUTE, method);
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return new JaxRsMutableHeadersMultivaluedMap(mutableHttpRequest.getHeaders());
    }

    @Override
    public String getHeaderString(String name) {
        return jaxRsHttpHeaders.getHeaderString(name);
    }

    // @Override v4
    public boolean containsHeaderString(String name, String valueSeparatorRegex, Predicate<String> valuePredicate) {
        return jaxRsHttpHeaders.containsHeaderString(name, valueSeparatorRegex, valuePredicate);
    }

    // @Override v4
    public boolean containsHeaderString(String name, Predicate<String> valuePredicate) {
        return jaxRsHttpHeaders.containsHeaderString(name, valuePredicate);
    }

    @Override
    public Date getDate() {
        return jaxRsHttpHeaders.getDate();
    }

    @Override
    public Locale getLanguage() {
        return jaxRsHttpHeaders.getLanguage();
    }

    @Override
    public int getLength() {
        return jaxRsHttpHeaders.getLength();
    }

    @Override
    public MediaType getMediaType() {
        return jaxRsHttpHeaders.getMediaType();
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        return jaxRsHttpHeaders.getAcceptableMediaTypes();
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        return jaxRsHttpHeaders.getAcceptableLanguages();
    }

    @Override
    public Map<String, Cookie> getCookies() {
        return jaxRsHttpHeaders.getCookies();
    }

    @Override
    public boolean hasEntity() {
        if (entityStream != null || mutableHttpRequest.getBody().isPresent() || mutableHttpRequest.getContentLength() > 0) {
            return true;
        }
        return Optional.ofNullable(serverHttpRequest)
            .flatMap(request -> request.byteBody().expectedLength().stream().boxed().findFirst())
            .orElse(0L) > 0;
    }

    @Override
    public InputStream getEntityStream() {
        if (entityStream != null) {
            return entityStream;
        }
        byte[] body = Optional.ofNullable(serverHttpRequest)
            .map(JaxRsContainerRequestContext::readBody)
            .orElseGet(() -> mutableHttpRequest.getBody(byte[].class).orElseGet(() -> new byte[0]));
        entityStream = new ByteArrayInputStream(body);
        return entityStream;
    }

    @Override
    public void setEntityStream(InputStream input) {
        checkRequestFilteringInProgress();
        entityStream = input;
    }

    @Override
    public SecurityContext getSecurityContext() {
        if (securityContext == null) {
            securityContext = mutableHttpRequest.getAttribute(SimpleSecurityContextBinder.SECURITY_CONTEXT_ATTRIBUTE, SecurityContext.class)
                .orElseGet(JaxRsContextSecurityContext::new);
        }
        return securityContext;
    }

    @Override
    public void setSecurityContext(SecurityContext context) {
        checkRequestFilteringInProgress();
        securityContext = context;
        mutableHttpRequest.setAttribute(SimpleSecurityContextBinder.SECURITY_CONTEXT_ATTRIBUTE, context);
    }

    @Override
    public void abortWith(Response response) {
        checkRequestFilteringInProgress();
        this.response = response;
    }

    public Response getResponse() {
        return response;
    }

    public void finished() {
        this.finished = true;
    }

    private void checkRequestFilteringInProgress() {
        if (finished) {
            throw new IllegalStateException("Request is already commited");
        }
    }

    private void checkIsRequestPreMatchingInProgress() {
        if (!preMatching) {
            throw new IllegalStateException("Pre matching is not in progress");
        }
    }

    private void setMutableRequestUri(URI requestUri) {
        mutableHttpRequest.uri(requestUri);
        mutableHttpRequest.setAttribute(REQUEST_URI_ATTRIBUTE, requestUri);
    }

    private static byte[] readBody(ServerHttpRequest<?> request) {
        try (CloseableAvailableByteBody body = request.byteBody()
            .split(ByteBody.SplitBackpressureMode.FASTEST)
            .buffer()
            .join()) {
            return body.toByteArray();
        }
    }
}
