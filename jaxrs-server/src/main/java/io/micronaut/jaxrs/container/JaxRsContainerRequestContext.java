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

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final MutableHttpRequest<?> mutableHttpRequest;
    private final JaxRsHttpHeaders jaxRsHttpHeaders;
    @Nullable
    private Response response;
    private final ApplicationProvider applicationProvider;
    private boolean finished;
    private final boolean preMatching = false; // TODO: Support pre matching in Micronaut

    // the security context of the request, unless a filter replaced it
    private @Nullable Supplier<SecurityContext> securityContext;

    JaxRsContainerRequestContext(MutableHttpRequest<?> mutableHttpRequest, ApplicationProvider applicationProvider) {
        this.mutableHttpRequest = mutableHttpRequest;
        this.applicationProvider = applicationProvider;
        this.jaxRsHttpHeaders = JaxRsMutableHttpHeaders.forRequest(mutableHttpRequest.getHeaders());
    }

    /**
     * @param securityContext The security context of the request
     * @return This
     */
    JaxRsContainerRequestContext withSecurityContext(Supplier<SecurityContext> securityContext) {
        this.securityContext = securityContext;
        return this;
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
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Request getRequest() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getMethod() {
        return mutableHttpRequest.getMethod().name();
    }

    @Override
    public void setMethod(String method) {
        checkIsRequestPreMatchingInProgress();
        throw new IllegalArgumentException("Not supported");
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
        return mutableHttpRequest.getBody().isPresent();
    }

    @Override
    public @Nullable InputStream getEntityStream() {
        return null;
    }

    @Override
    public void setEntityStream(InputStream input) {
        checkRequestFilteringInProgress();
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
        if (!preMatching) {
            throw new IllegalStateException("Request is already commited");
        }
    }
}
