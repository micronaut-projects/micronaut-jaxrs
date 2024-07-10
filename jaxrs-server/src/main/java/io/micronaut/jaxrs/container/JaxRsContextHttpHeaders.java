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
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerHttpRequestContext;
import io.micronaut.jaxrs.common.JaxRsHttpHeaders;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The bean context implementation of {@link jakarta.ws.rs.core.HttpHeaders}.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
final class JaxRsContextHttpHeaders implements jakarta.ws.rs.core.HttpHeaders {

    private JaxRsHttpHeaders getHeaders() {
        HttpRequest<Object> httpRequest = ServerHttpRequestContext.get();
        if (httpRequest == null) {
            throw new IllegalStateException("Cannot find required request");
        }
        return JaxRsHttpHeaders.forRequest(httpRequest.getHeaders());
    }

    @Override
    public List<String> getRequestHeader(String name) {
        return getHeaders().getRequestHeader(name);
    }

    @Override
    public String getHeaderString(String name) {
        return getHeaders().getHeaderString(name);
    }

//    @Override v4
    public boolean containsHeaderString(String name, String valueSeparatorRegex, Predicate<String> valuePredicate) {
        return getHeaders().containsHeaderString(name, valueSeparatorRegex, valuePredicate);
    }

//    @Override v4
    public boolean containsHeaderString(String name, Predicate<String> valuePredicate) {
        return getHeaders().containsHeaderString(name, valuePredicate);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
        return getHeaders().getRequestHeaders();
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        return getHeaders().getAcceptableMediaTypes();
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        return getHeaders().getAcceptableLanguages();
    }

    @Override
    public MediaType getMediaType() {
        return getHeaders().getMediaType();
    }

    @Override
    public Locale getLanguage() {
        return getHeaders().getLanguage();
    }

    @Override
    public Map<String, Cookie> getCookies() {
        return getHeaders().getCookies();
    }

    @Override
    public Date getDate() {
        return getHeaders().getDate();
    }

    @Override
    public int getLength() {
        return getHeaders().getLength();
    }

}
