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
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsHeaderUtil;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.CompletionStageRxInvoker;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.RxInvoker;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.net.URI;
import java.util.Locale;

/**
 * The implementation of {@link Invocation.Builder}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsInvocationBuilder implements Invocation.Builder {

    private final JaxRsClient client;
    private final URI uri;
    private final MutableHttpHeaders mutableHttpHeaders = new SimpleHttpHeaders(ConversionService.SHARED);
    private final JaxRsConfiguration configuration;

    JaxRsInvocationBuilder(JaxRsClient client, URI uri, JaxRsConfiguration configuration) {
        this.client = client;
        this.uri = uri;
        this.configuration = configuration;
    }

    @Override
    public JaxRsInvocation build(String method) {
        return new JaxRsInvocation(client, uri, method, null, mutableHttpHeaders, configuration);
    }

    @Override
    public JaxRsInvocation build(String method, Entity<?> entity) {
        return new JaxRsInvocation(client, uri, method, entity, mutableHttpHeaders, configuration);
    }

    @Override
    public JaxRsInvocation buildGet() {
        return new JaxRsInvocation(client, uri, HttpMethod.GET.name(), null, mutableHttpHeaders, configuration);
    }

    @Override
    public JaxRsInvocation buildDelete() {
        return new JaxRsInvocation(client, uri, HttpMethod.DELETE.name(), null, mutableHttpHeaders, configuration);
    }

    @Override
    public JaxRsInvocation buildPost(Entity<?> entity) {
        return new JaxRsInvocation(client, uri, HttpMethod.POST.name(), entity, mutableHttpHeaders, configuration);
    }

    @Override
    public JaxRsInvocation buildPut(Entity<?> entity) {
        return new JaxRsInvocation(client, uri, HttpMethod.PUT.name(), entity, mutableHttpHeaders, configuration);
    }

    @Override
    public AsyncInvoker async() {
        return new JaxRsInvocation(client, uri, HttpMethod.PUT.name(), null, mutableHttpHeaders, configuration);
    }

    @Override
    public Invocation.Builder accept(String... mediaTypes) {
        for (String mediaType : mediaTypes) {
            mutableHttpHeaders.add(HttpHeaders.ACCEPT, mediaType);
        }
        return this;
    }

    @Override
    public Invocation.Builder accept(MediaType... mediaTypes) {
        for (MediaType mediaType : mediaTypes) {
            mutableHttpHeaders.add(HttpHeaders.ACCEPT, mediaType.toString());
        }
        return this;
    }

    @Override
    public Invocation.Builder acceptLanguage(Locale... locales) {
        for (Locale locale : locales) {
            mutableHttpHeaders.add(HttpHeaders.ACCEPT_LANGUAGE, locale.toLanguageTag());
        }
        return this;
    }

    @Override
    public Invocation.Builder acceptLanguage(String... locales) {
        for (String locale : locales) {
            mutableHttpHeaders.add(HttpHeaders.ACCEPT_LANGUAGE, locale);
        }
        return this;
    }

    @Override
    public Invocation.Builder acceptEncoding(String... encodings) {
        for (String encoding : encodings) {
            mutableHttpHeaders.add(HttpHeaders.ACCEPT_ENCODING, encoding);
        }
        return this;
    }

    @Override
    public Invocation.Builder cookie(Cookie cookie) {
        mutableHttpHeaders.add(HttpHeaders.COOKIE, toRuntimeString(cookie));
        return this;
    }

    @Override
    public Invocation.Builder cookie(String name, String value) {
        return cookie(new Cookie.Builder(name).value(value).build());
    }

    @Override
    public Invocation.Builder cacheControl(CacheControl cacheControl) {
        mutableHttpHeaders.add(HttpHeaders.CACHE_CONTROL, toRuntimeString(cacheControl));
        return this;
    }

    @Override
    public Invocation.Builder header(String name, Object value) {
        mutableHttpHeaders.add(name, JaxRsHeaderUtil.headerToString(value));
        return this;
    }

    @Override
    public Invocation.Builder headers(MultivaluedMap<String, Object> headers) {
        for (String header : mutableHttpHeaders.asMap().keySet()) {
            mutableHttpHeaders.remove(header);
        }
        if (headers != null) {
            headers.forEach((key, values) -> values.forEach(value -> mutableHttpHeaders.add(key, JaxRsHeaderUtil.headerToString(value))));
        }
        return this;
    }

    @Override
    public Invocation.Builder property(String name, Object value) {
        return this;
    }

    @Override
    public CompletionStageRxInvoker rx() {
        return new JaxRsInvocation(client, uri, null, null, mutableHttpHeaders, configuration);
    }

    @Override
    public <T extends RxInvoker> T rx(Class<T> clazz) {
        throw new IllegalStateException("unsupported");
    }

    @Override
    public Response get() {
        return buildGet().invoke(Response.class);
    }

    @Override
    public <T> T get(Class<T> responseType) {
        return buildGet().invoke(responseType);
    }

    @Override
    public <T> T get(GenericType<T> responseType) {
        return buildGet().invoke(responseType);
    }

    @Override
    public Response put(Entity<?> entity) {
        return buildPut(entity).invoke(Response.class);
    }

    @Override
    public <T> T put(Entity<?> entity, Class<T> responseType) {
        return buildPut(entity).invoke(responseType);
    }

    @Override
    public <T> T put(Entity<?> entity, GenericType<T> responseType) {
        return buildPut(entity).invoke(responseType);
    }

    @Override
    public Response post(Entity<?> entity) {
        return buildPost(entity).invoke(Response.class);
    }

    @Override
    public <T> T post(Entity<?> entity, Class<T> responseType) {
        return buildPost(entity).invoke(responseType);
    }

    @Override
    public <T> T post(Entity<?> entity, GenericType<T> responseType) {
        return buildPost(entity).invoke(responseType);
    }

    @Override
    public Response delete() {
        return buildDelete().invoke(Response.class);
    }

    @Override
    public <T> T delete(Class<T> responseType) {
        return buildDelete().invoke(responseType);
    }

    @Override
    public <T> T delete(GenericType<T> responseType) {
        return buildDelete().invoke(responseType);
    }

    @Override
    public Response head() {
        return build(HttpMethod.HEAD.name()).invoke(Response.class);
    }

    private Invocation buildOption() {
        return build(HttpMethod.OPTIONS.name());
    }

    @Override
    public Response options() {
        return buildOption().invoke(Response.class);
    }

    @Override
    public <T> T options(Class<T> responseType) {
        return buildOption().invoke(responseType);
    }

    @Override
    public <T> T options(GenericType<T> responseType) {
        return buildOption().invoke(responseType);
    }

    private Invocation buildTrace() {
        return build(HttpMethod.TRACE.name());
    }

    @Override
    public Response trace() {
        return buildTrace().invoke(Response.class);
    }

    @Override
    public <T> T trace(Class<T> responseType) {
        return buildTrace().invoke(responseType);
    }

    @Override
    public <T> T trace(GenericType<T> responseType) {
        return buildTrace().invoke(responseType);
    }

    @Override
    public Response method(String name) {
        return build(name).invoke(Response.class);
    }

    @Override
    public <T> T method(String name, Class<T> responseType) {
        return build(name).invoke(responseType);
    }

    @Override
    public <T> T method(String name, GenericType<T> responseType) {
        return build(name).invoke(responseType);
    }

    @Override
    public Response method(String name, Entity<?> entity) {
        return build(name).invokeExchange(Argument.of(Response.class), entity);
    }

    @Override
    public <T> T method(String name, Entity<?> entity, Class<T> responseType) {
        return build(name).invokeExchange(Argument.of(responseType), entity);
    }

    @Override
    public <T> T method(String name, Entity<?> entity, GenericType<T> responseType) {
        return build(name).invokeExchange(JaxRsArgumentUtil.from(responseType), entity);
    }

    private <T> String toRuntimeString(T value) {
        return RuntimeDelegate.getInstance().createHeaderDelegate((Class<T>) value.getClass()).toString(value);
    }
}
