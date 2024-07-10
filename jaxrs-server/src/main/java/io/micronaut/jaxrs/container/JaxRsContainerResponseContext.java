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
import io.micronaut.core.type.Argument;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.jaxrs.common.ArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.jaxrs.common.JaxRsUtils;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The implementation of {@link ContainerResponseContext}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsContainerResponseContext implements ContainerResponseContext {

    private final MutableHttpResponse<?> mutableHttpResponse;
    private final JaxRsMutableResponse jaxRsMutableResponse;
    private Argument<?> bodyArgument;

    public JaxRsContainerResponseContext(MutableHttpResponse<?> mutableHttpResponse, Argument<?> bodyArgument) {
        this.mutableHttpResponse = mutableHttpResponse;
        this.jaxRsMutableResponse = new JaxRsMutableResponse(mutableHttpResponse);
        this.bodyArgument = bodyArgument;
    }

    @Override
    public int getStatus() {
        return mutableHttpResponse.getStatus().getCode();
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
        mutableHttpResponse.status(statusInfo.getStatusCode());
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        return jaxRsMutableResponse.getHeaders();
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        return jaxRsMutableResponse.getStringHeaders();
    }

    @Override
    public String getHeaderString(String name) {
        return jaxRsMutableResponse.getHeaderString(name);
    }

    // @Override v4
    public boolean containsHeaderString(String name, String valueSeparatorRegex, Predicate<String> valuePredicate) {
        return JaxRsHttpHeaders.forResponse(mutableHttpResponse.getHeaders()).containsHeaderString(name, valueSeparatorRegex, valuePredicate);
    }

    // @Override v4
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
    public Object getEntity() {
        return jaxRsMutableResponse.getEntity();
    }

    @Override
    public Class<?> getEntityClass() {
        return bodyArgument.getType();
    }

    @Override
    public Type getEntityType() {
        return bodyArgument.asType();
    }

    @Override
    public void setEntity(Object entity) {
        mutableHttpResponse.body(entity);
    }

    @Override
    public void setEntity(Object entity, Annotation[] annotations, MediaType mediaType) {
        mutableHttpResponse.body(entity);
        if (mediaType != null) {
            mutableHttpResponse.contentType(JaxRsUtils.convert(mediaType));
        }
        bodyArgument = Argument.of(entity == null ? (Class) Object.class : entity.getClass(), ArgumentUtil.createAnnotationMetadata(annotations));
    }

    @Override
    public Annotation[] getEntityAnnotations() {
        return bodyArgument.getAnnotationMetadata().synthesizeAll();
    }

    @Override
    public OutputStream getEntityStream() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEntityStream(OutputStream outputStream) {
        throw new UnsupportedOperationException();
    }

    public Argument<?> getBodyArgument() {
        return bodyArgument;
    }
}
