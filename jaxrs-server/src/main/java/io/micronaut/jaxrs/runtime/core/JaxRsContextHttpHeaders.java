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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerHttpRequestContext;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bean context implementation of {@link jakarta.ws.rs.core.HttpHeaders}.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
public final class JaxRsContextHttpHeaders implements jakarta.ws.rs.core.HttpHeaders {

    @Override
    public List<String> getRequestHeader(String name) {
        return findRequiredRequest().getHeaders().getAll(name);
    }

    @Override
    public String getHeaderString(String name) {
        return findRequiredRequest().getHeaders().get(name);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
        return null;
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        return List.of();
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        return List.of();
    }

    @Override
    public MediaType getMediaType() {
        return null;
    }

    @Override
    public Locale getLanguage() {
        return null;
    }

    @Override
    public Map<String, Cookie> getCookies() {
        return Map.of();
    }

    @Override
    public Date getDate() {
        return null;
    }

    @Override
    public int getLength() {
        return (int) findRequiredRequest().getContentLength();
    }

    private HttpRequest<?> findRequiredRequest() {
        HttpRequest<Object> httpRequest = ServerHttpRequestContext.get();
        if (httpRequest == null) {
            throw new IllegalStateException("Cannot find required request");
        }
        return httpRequest;
    }
}
