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
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsMutableObjectHeadersMultivaluedMap;
import io.micronaut.jaxrs.common.JaxRsUtils;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
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
    private MutableHttpRequest<?> mutableHttpRequest;
    private JaxRsHttpHeaders jaxRsHttpHeaders;
    private Response response;
    private Argument<?> bodyType;
    private Annotation[] annotations;
    private ByteArrayOutputStream entityStream;

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
        return mutableHttpRequest.getMethod().name();
    }

    @Override
    public void setMethod(String method) {
        HttpMethod httpMethod = HttpMethod.parse(method);
        MutableHttpRequest<Object> replacement = httpMethod == HttpMethod.CUSTOM
            ? HttpRequest.create(HttpMethod.CUSTOM, mutableHttpRequest.getUri().toString(), method)
            : HttpRequest.create(httpMethod, mutableHttpRequest.getUri().toString());
        mutableHttpRequest.getHeaders().forEachValue(replacement::header);
        try {
            mutableHttpRequest.getCookies().getAll().forEach(replacement::cookie);
        } catch (UnsupportedOperationException e) {
            // Some client request implementations expose cookies only through headers.
        }
        mutableHttpRequest.getBody().ifPresent(replacement::body);
        mutableHttpRequest = replacement;
        jaxRsHttpHeaders = JaxRsMutableHttpHeaders.forRequest(replacement.getHeaders());
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
        if (entityStream == null) {
            entityStream = new ByteArrayOutputStream();
        }
        return entityStream;
    }

    @Override
    public void setEntityStream(OutputStream outputStream) {
        ByteArrayOutputStream target = entityStream;
        if (target != null) {
            target.reset();
        }
        try {
            outputStream.write(entityBytes());
            outputStream.flush();
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
        byte[] bytes;
        if (target != null) {
            bytes = target.toByteArray();
        } else if (outputStream instanceof ByteArrayOutputStream byteArrayOutputStream) {
            bytes = byteArrayOutputStream.toByteArray();
        } else {
            return;
        }
        mutableHttpRequest.body(bytes);
        bodyType = Argument.of(byte[].class);
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

    MutableHttpRequest<?> getMutableHttpRequest() {
        return mutableHttpRequest;
    }

    private byte[] entityBytes() {
        Object body = mutableHttpRequest.getBody().orElse(null);
        if (body == null) {
            return new byte[0];
        }
        if (body instanceof byte[] bytes) {
            return bytes;
        }
        if (body instanceof ByteBuffer<?> byteBuffer) {
            return byteBuffer.toByteArray();
        }
        return ConversionService.SHARED.convert(body, byte[].class)
            .orElseGet(() -> body.toString().getBytes(StandardCharsets.UTF_8));
    }
}
