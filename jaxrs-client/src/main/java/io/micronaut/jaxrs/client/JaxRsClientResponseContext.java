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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.jaxrs.common.JaxRsHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The implementation of {@link ClientResponseContext}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsClientResponseContext implements ClientResponseContext {

    private final JaxRsMutableResponse jaxRsMutableResponse;
    private final MutableHttpResponse<?> mutableHttpResponse;

    public JaxRsClientResponseContext(JaxRsMutableResponse jaxRsMutableResponse) {
        this.jaxRsMutableResponse = jaxRsMutableResponse;
        this.mutableHttpResponse = jaxRsMutableResponse.getResponse();
    }

    @Override
    public int getStatus() {
        return jaxRsMutableResponse.getStatus();
    }

    @Override
    public void setStatus(int code) {
        mutableHttpResponse.status(code);
    }

    @Override
    public Response.StatusType getStatusInfo() {
        return jaxRsMutableResponse.getStatusInfo();
    }

    @Override
    public void setStatusInfo(Response.StatusType statusInfo) {
        mutableHttpResponse.status(statusInfo.getStatusCode(), statusInfo.getReasonPhrase());
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return jaxRsMutableResponse.getStringHeaders();
    }

    @Override
    public String getHeaderString(String name) {
        return jaxRsMutableResponse.getHeaderString(name);
    }

    @Override
    public boolean containsHeaderString(String name, String valueSeparatorRegex, Predicate<String> valuePredicate) {
        return JaxRsHttpHeaders.forResponse(mutableHttpResponse.getHeaders()).containsHeaderString(name, valueSeparatorRegex, valuePredicate);
    }

    @Override
    public boolean containsHeaderString(String name, Predicate<String> valuePredicate) {
        return JaxRsHttpHeaders.forResponse(mutableHttpResponse.getHeaders()).containsHeaderString(name, valuePredicate);
    }

    @Override
    public Set<String> getAllowedMethods() {
        return jaxRsMutableResponse.getAllowedMethods();
    }

    @Override
    public Date getDate() {
        return jaxRsMutableResponse.getDate();
    }

    @Override
    public Locale getLanguage() {
        return jaxRsMutableResponse.getLanguage();
    }

    @Override
    public int getLength() {
        return jaxRsMutableResponse.getLength();
    }

    @Override
    public MediaType getMediaType() {
        return jaxRsMutableResponse.getMediaType();
    }

    @Override
    public Map<String, NewCookie> getCookies() {
        return jaxRsMutableResponse.getCookies();
    }

    @Override
    public EntityTag getEntityTag() {
        return jaxRsMutableResponse.getEntityTag();
    }

    @Override
    public Date getLastModified() {
        return jaxRsMutableResponse.getLastModified();
    }

    @Override
    public URI getLocation() {
        return jaxRsMutableResponse.getLocation();
    }

    @Override
    public Set<Link> getLinks() {
        return jaxRsMutableResponse.getLinks();
    }

    @Override
    public boolean hasLink(String relation) {
        return jaxRsMutableResponse.hasLink(relation);
    }

    @Override
    public Link getLink(String relation) {
        return jaxRsMutableResponse.getLink(relation);
    }

    @Override
    public Link.Builder getLinkBuilder(String relation) {
        return jaxRsMutableResponse.getLinkBuilder(relation);
    }

    @Override
    public boolean hasEntity() {
        return jaxRsMutableResponse.hasEntity();
    }

    @Override
    public InputStream getEntityStream() {
        return jaxRsMutableResponse.getEntityStream();
    }

    @Override
    public void setEntityStream(InputStream input) {
        jaxRsMutableResponse.setEntityStream(input);
    }

}
