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
import io.micronaut.core.type.Argument;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableObjectHeadersMultivaluedMap;
import io.micronaut.jaxrs.common.JaxRsUtils;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The implementation of {@link ClientRequestContext}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsClientRequestContext implements ClientRequestContext {

    private final Client client;
    private final Configuration configuration;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final MutableHttpRequest<?> mutableHttpRequest;
    private final JaxRsHttpHeaders jaxRsHttpHeaders;
    private Response response;
    private Argument<?> bodyType;
    private Annotation[] annotations;

    public JaxRsClientRequestContext(Client client,
                                     Configuration configuration,
                                     MutableHttpRequest<?> mutableHttpRequest,
                                     Argument<?> bodyType) {
        this.client = client;
        this.configuration = configuration;
        this.mutableHttpRequest = mutableHttpRequest;
        this.jaxRsHttpHeaders = JaxRsMutableHttpHeaders.forRequest(mutableHttpRequest.getHeaders());
        this.bodyType = bodyType;
    }

    @Override
    public Object getProperty(String name) {
        return properties.get(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public boolean hasProperty(String name) {
        return properties.containsKey(name);
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
    public URI getUri() {
        return mutableHttpRequest.getUri();
    }

    @Override
    public void setUri(URI uri) {
        mutableHttpRequest.uri(uri);
    }

    @Override
    public String getMethod() {
        return mutableHttpRequest.getMethod().name();
    }

    @Override
    public void setMethod(String method) {
        throw new IllegalArgumentException("Not supported");
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        return new JaxRsMutableObjectHeadersMultivaluedMap(mutableHttpRequest.getHeaders());
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        return jaxRsHttpHeaders.getRequestHeaders();
    }

    @Override
    public String getHeaderString(String name) {
        return jaxRsHttpHeaders.getHeaderString(name);
    }

    // @Override v4
    public boolean containsHeaderString(String name, String valueSeparatorRegex, Predicate<String> valuePredicate) {
        return JaxRsHttpHeaders.forRequest(mutableHttpRequest.getHeaders()).containsHeaderString(name, valueSeparatorRegex, valuePredicate);
    }

    // @Override v4
    public boolean containsHeaderString(String name, Predicate<String> valuePredicate) {
        return JaxRsHttpHeaders.forRequest(mutableHttpRequest.getHeaders()).containsHeaderString(name, valuePredicate);
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
        return mutableHttpRequest.getBody(bodyType).isPresent();
    }

    @Override
    public Object getEntity() {
        return mutableHttpRequest.getBody(bodyType).orElse(null);
    }

    @Override
    public void setEntity(Object entity) {
        mutableHttpRequest.body(entity);
        bodyType = Argument.of(entity.getClass());
    }

    @Override
    public Class<?> getEntityClass() {
        return bodyType.getType();
    }

    @Override
    public Type getEntityType() {
        return bodyType.asType();
    }

    @Override
    public void setEntity(Object entity, Annotation[] annotations, MediaType mediaType) {
        mutableHttpRequest.body(entity);
        if (mediaType != null) {
            mutableHttpRequest.contentType(JaxRsUtils.convert(mediaType));
        }
        bodyType = Argument.of(entity.getClass());
        if (annotations != null) {
            this.annotations = annotations;
            bodyType = Argument.of(bodyType.getType(), JaxRsArgumentUtil.createAnnotationMetadata(annotations), bodyType.getTypeParameters());
        }
    }

    @Override
    public Annotation[] getEntityAnnotations() {
        if (annotations != null) {
            return annotations;
        }
        return bodyType.getAnnotationMetadata().synthesizeAll();
    }

    @Override
    public OutputStream getEntityStream() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEntityStream(OutputStream outputStream) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Client getClient() {
        return client;
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void abortWith(Response response) {
        this.response = response;
    }

    public Response getResponse() {
        return response;
    }
}
