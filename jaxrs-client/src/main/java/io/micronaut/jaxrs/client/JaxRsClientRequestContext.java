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
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    @Nullable
    private Response response;
    private Argument<?> bodyType;
    private Annotation @Nullable [] annotations;
    // the method a filter changed the request to
    private @Nullable String method;
    // the entity stream a filter replaced, which writes to the entity sink
    private @Nullable OutputStream entityStream;
    private @Nullable ByteArrayOutputStream entitySink;

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
    public @Nullable Object getProperty(String name) {
        return properties.get(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return Collections.unmodifiableSet(properties.keySet());
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
        return method != null ? method : mutableHttpRequest.getMethodName();
    }

    @Override
    public void setMethod(String method) {
        this.method = Objects.requireNonNull(method, "method");
    }

    /**
     * @return The method a filter changed the request to, {@code null} if unchanged
     */
    @Nullable String getChangedMethod() {
        return method;
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
    public @Nullable String getHeaderString(String name) {
        return jaxRsHttpHeaders.getHeaderString(name);
    }

    @Override
    public boolean containsHeaderString(String name, String valueSeparatorRegex, Predicate<String> valuePredicate) {
        return JaxRsHttpHeaders.forRequest(mutableHttpRequest.getHeaders()).containsHeaderString(name, valueSeparatorRegex, valuePredicate);
    }

    @Override
    public boolean containsHeaderString(String name, Predicate<String> valuePredicate) {
        return JaxRsHttpHeaders.forRequest(mutableHttpRequest.getHeaders()).containsHeaderString(name, valuePredicate);
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
        return mutableHttpRequest.getBody(bodyType).isPresent();
    }

    @Override
    public @Nullable Object getEntity() {
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
            mutableHttpRequest.contentType(Objects.requireNonNull(JaxRsUtils.convert(mediaType)));
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
        if (entityStream != null) {
            return entityStream;
        }
        if (entitySink == null) {
            entitySink = new ByteArrayOutputStream();
        }
        return entitySink;
    }

    @Override
    public void setEntityStream(OutputStream outputStream) {
        getEntityStream();
        this.entityStream = Objects.requireNonNull(outputStream, "outputStream");
    }

    /**
     * Write the entity through the entity stream a filter replaced.
     *
     * @param entity The written entity
     * @return The entity as the stream wrote it, or the given one when no filter replaced it
     * @throws IOException If the stream fails
     */
    byte[] writeThroughEntityStream(byte[] entity) throws IOException {
        if (entityStream == null || entitySink == null) {
            return entity;
        }
        entityStream.write(entity);
        entityStream.flush();
        entityStream.close();
        return entitySink.toByteArray();
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

    public @Nullable Response getResponse() {
        return response;
    }
}
