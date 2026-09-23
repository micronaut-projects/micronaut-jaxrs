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
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.jaxrs.common.JaxRsHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableHeadersMultivaluedMap;
import io.micronaut.jaxrs.common.JaxRsMutableHttpHeaders;
import io.micronaut.jaxrs.runtime.ext.bind.UriInfoImpl;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Predicate;

/**
 * The implementation of {@link ContainerRequestContext}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsContainerRequestContext implements ContainerRequestContext {

    /**
     * The request attribute with the entity stream a request filter set, which the resource method
     * reads its entity from.
     */
    static final String ENTITY_STREAM = JaxRsContainerRequestContext.class.getName() + ".entityStream";

    // the properties of the request, which all its contexts share, and the attributes of the stub
    // servlet request
    private static final String PROPERTIES = JaxRsContainerRequestContext.class.getName() + ".properties";

    private final Map<String, Object> properties;
    // the request the filters continue with: a pre-matching filter may change its method
    private MutableHttpRequest<?> mutableHttpRequest;
    // the request as received, which has the body
    private final MutableHttpRequest<?> bodyRequest;
    private final JaxRsHttpHeaders jaxRsHttpHeaders;
    @Nullable
    private Response response;
    private final ApplicationProvider applicationProvider;
    private boolean finished;
    private final boolean preMatching;
    private boolean methodChanged;

    // the security context of the request, unless a filter replaced it
    private @Nullable Supplier<SecurityContext> securityContext;

    private @Nullable Supplier<Request> request;

    JaxRsContainerRequestContext(MutableHttpRequest<?> mutableHttpRequest, ApplicationProvider applicationProvider, boolean preMatching) {
        this.mutableHttpRequest = mutableHttpRequest;
        this.bodyRequest = mutableHttpRequest;
        this.preMatching = preMatching;
        this.applicationProvider = applicationProvider;
        this.jaxRsHttpHeaders = JaxRsMutableHttpHeaders.forRequest(mutableHttpRequest.getHeaders());
        this.properties = properties(mutableHttpRequest);
    }

    /**
     * The properties of a request: the ones of the filter and interceptor contexts, and the
     * attributes of its stub servlet request.
     *
     * @param request The request
     * @return The properties, created on first use
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> properties(HttpRequest<?> request) {
        Map<String, Object> properties = request.getAttribute(PROPERTIES, Map.class).orElse(null);
        if (properties == null) {
            properties = new LinkedHashMap<>();
            request.setAttribute(PROPERTIES, properties);
        }
        return properties;
    }

    /**
     * @param securityContext The security context of the request
     * @return This
     */
    JaxRsContainerRequestContext withSecurityContext(Supplier<SecurityContext> securityContext) {
        this.securityContext = securityContext;
        return this;
    }

    /**
     * @param request The request of the context
     * @return This
     */
    JaxRsContainerRequestContext withRequest(Supplier<Request> request) {
        this.request = request;
        return this;
    }

    /**
     * @return The request with the method a pre-matching filter changed, {@code null} if no filter
     * changed it
     */
    @Nullable MutableHttpRequest<?> getMethodChangedRequest() {
        return methodChanged ? mutableHttpRequest : null;
    }

    @Override
    public @Nullable Object getProperty(String name) {
        return properties.get(name);
    }

    @Override
    public boolean hasProperty(String name) {
        return properties.containsKey(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public void setProperty(String name, Object object) {
        properties.put(name, object);
    }

    @Override
    public void removeProperty(String name) {
        properties.remove(name);
    }

    @Override
    public UriInfo getUriInfo() {
        return new UriInfoImpl(mutableHttpRequest, applicationProvider.getPath(), applicationProvider.getContextPath());
    }

    @Override
    public void setRequestUri(URI requestUri) {
        checkIsRequestPreMatchingInProgress();
        mutableHttpRequest.uri(requestUri);
    }

    @Override
    public void setRequestUri(URI baseUri, URI requestUri) {
        checkIsRequestPreMatchingInProgress();
        mutableHttpRequest.uri(baseUri.resolve(requestUri));
    }

    @Override
    public Request getRequest() {
        if (request == null) {
            throw new IllegalStateException("No request");
        }
        return request.get();
    }

    @Override
    public String getMethod() {
        return mutableHttpRequest.getMethod().name();
    }

    @Override
    public void setMethod(String method) {
        checkIsRequestPreMatchingInProgress();
        mutableHttpRequest = new JaxRsMethodHttpRequest<>(mutableHttpRequest, method);
        methodChanged = true;
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return new JaxRsMutableHeadersMultivaluedMap(mutableHttpRequest.getHeaders());
    }

    @Override
    public @Nullable String getHeaderString(String name) {
        return jaxRsHttpHeaders.getHeaderString(name);
    }

    @Override
    public boolean containsHeaderString(String name, String valueSeparatorRegex, Predicate<String> valuePredicate) {
        return jaxRsHttpHeaders.containsHeaderString(name, valueSeparatorRegex, valuePredicate);
    }

    @Override
    public boolean containsHeaderString(String name, Predicate<String> valuePredicate) {
        return jaxRsHttpHeaders.containsHeaderString(name, valuePredicate);
    }

    @Override
    public @Nullable Date getDate() {
        return jaxRsHttpHeaders.getDate();
    }

    @Override
    public @Nullable Locale getLanguage() {
        return jaxRsHttpHeaders.getLanguage();
    }

    @Override
    public int getLength() {
        return jaxRsHttpHeaders.getLength();
    }

    @Override
    public @Nullable MediaType getMediaType() {
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
        if (mutableHttpRequest.getAttribute(ENTITY_STREAM).isPresent()) {
            return true;
        }
        HttpHeaders headers = bodyRequest.getHeaders();
        return bodyRequest.getContentLength() > 0 || headers.contains(HttpHeaders.TRANSFER_ENCODING) || bodyRequest.getBody().isPresent();
    }

    @Override
    public InputStream getEntityStream() {
        InputStream replaced = mutableHttpRequest.getAttribute(ENTITY_STREAM, InputStream.class).orElse(null);
        if (replaced != null) {
            return replaced;
        }
        if (bodyRequest instanceof ServerHttpRequest<?> serverHttpRequest) {
            // a copy: the resource method still reads the entity
            return serverHttpRequest.byteBody().split().toInputStream();
        }
        return bodyRequest.getBody(byte[].class)
            .<InputStream>map(ByteArrayInputStream::new)
            .orElseGet(InputStream::nullInputStream);
    }

    @Override
    public void setEntityStream(InputStream input) {
        checkRequestFilteringInProgress();
        mutableHttpRequest.setAttribute(ENTITY_STREAM, input);
    }

    @Override
    public @Nullable SecurityContext getSecurityContext() {
        return securityContext == null ? null : securityContext.get();
    }

    @Override
    public void setSecurityContext(SecurityContext context) {
        checkRequestFilteringInProgress();
        this.securityContext = () -> context;
    }

    @Override
    public void abortWith(Response response) {
        checkRequestFilteringInProgress();
        this.response = response;
    }

    public @Nullable Response getResponse() {
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
        if (!preMatching || finished) {
            throw new IllegalStateException("Request is already commited");
        }
    }
}
