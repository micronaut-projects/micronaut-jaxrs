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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequestWrapper;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpParameters;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.cookie.Cookie;
import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * The request a pre-matching filter changed the method of, with
 * {@code ContainerRequestContext.setMethod}: the route is matched with the method, and everything
 * else, e.g. the headers, the URI and the body, is the one of the request it wraps.
 *
 * @param <B> The type of the body
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsMethodHttpRequest<B> extends HttpRequestWrapper<B> implements MutableHttpRequest<B> {

    private final MutableHttpRequest<B> request;
    private final HttpMethod method;
    private final String methodName;

    /**
     * @param request The request
     * @param method  The name of the method
     */
    JaxRsMethodHttpRequest(MutableHttpRequest<B> request, String method) {
        super(request);
        this.request = request;
        this.method = HttpMethod.parse(method);
        this.methodName = method;
    }

    @Override
    public HttpMethod getMethod() {
        return method;
    }

    @Override
    public String getMethodName() {
        return methodName;
    }

    @Override
    public MutableHttpRequest<B> cookie(Cookie cookie) {
        request.cookie(cookie);
        return this;
    }

    @Override
    public MutableHttpRequest<B> uri(URI uri) {
        request.uri(uri);
        return this;
    }

    @Override
    public <T> MutableHttpRequest<T> body(@Nullable T body) {
        return new JaxRsMethodHttpRequest<>(request.body(body), methodName);
    }

    @Override
    public void setConversionService(ConversionService conversionService) {
        request.setConversionService(conversionService);
    }

    @Override
    public MutableHttpHeaders getHeaders() {
        return request.getHeaders();
    }

    @Override
    public MutableHttpParameters getParameters() {
        return request.getParameters();
    }
}
