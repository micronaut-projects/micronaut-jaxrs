/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.CollectionUtils;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Adapter class for JAR-RS headers.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public sealed class JaxRsHttpHeaders implements HttpHeaders permits JaxRsMutableHttpHeaders {

    private final io.micronaut.http.HttpHeaders httpHeaders;
    private final boolean isResponse;

    /**
     * Default constructor.
     *
     * @param httpHeaders The Micronaut headers
     * @param isResponse  Is response headers
     */
    JaxRsHttpHeaders(io.micronaut.http.HttpHeaders httpHeaders, boolean isResponse) {
        this.httpHeaders = httpHeaders;
        this.isResponse = isResponse;
    }

    /**
     * Create headers for a request.
     *
     * @param httpHeaders The headers
     * @return The headers
     */
    public static JaxRsHttpHeaders forRequest(io.micronaut.http.HttpHeaders httpHeaders) {
        return new JaxRsHttpHeaders(httpHeaders, false);
    }

    /**
     * Create headers for a response.
     *
     * @param httpHeaders The headers
     * @return The headers
     */
    public static JaxRsHttpHeaders forResponse(io.micronaut.http.HttpHeaders httpHeaders) {
        return new JaxRsHttpHeaders(httpHeaders, true);
    }

    @Override
    public List<String> getRequestHeader(String name) {
        return httpHeaders.getAll(name);
    }

    @Override
    public String getHeaderString(String name) {
        List<String> all = httpHeaders.getAll(name);
        if (all.isEmpty()) {
            return null;
        }
        return String.join(",", all);
    }

    // @Override v4
    public final boolean containsHeaderString(String name, String valueSeparatorRegex, Predicate<String> valuePredicate) {
        return httpHeaders.getAll(name)
            .stream()
            .flatMap(value -> Arrays.stream(value.split(valueSeparatorRegex)))
            .map(String::trim)
            .anyMatch(valuePredicate);
    }

    // @Override v4
    public final boolean containsHeaderString(String name, Predicate<String> valuePredicate) {
        return containsHeaderString(name, ",", valuePredicate);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
        return new JaxRsHeadersMultivaluedMap(httpHeaders);
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        return httpHeaders.getAll(io.micronaut.http.HttpHeaders.ACCEPT)
            .stream()
            .flatMap(text -> {
                int len = text.length();
                if (len == 0) {
                    return Stream.of(MediaType.valueOf(io.micronaut.http.MediaType.ALL));
                }
                if (text.indexOf(',') > -1) {
                    return Arrays.stream(text.split(","))
                        .map(str -> {
                            final int i = str.indexOf(';');
                            final MediaType mt = MediaType.valueOf(str);
                            if (i > -1) {
                                return new Weighted<>(mt, mt.getParameters());
                            } else {
                                return new Weighted<>(mt);
                            }
                        })
                        .sorted()
                        .map(Weighted::getObject)
                        .toList()
                        .stream();
                }
                return Stream.of(MediaType.valueOf(text));
            }).toList();
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        return httpHeaders.getAll(io.micronaut.http.HttpHeaders.ACCEPT_LANGUAGE)
            .stream()
            .flatMap(text -> {
                int len = text.length();
                if (len == 0 || (len == 1 && text.charAt(0) == '*')) {
                    return Stream.of();
                }
                if (text.indexOf(',') > -1) {
                    return Arrays.stream(text.split(","))
                        .map(str -> {
                            final int i = str.indexOf(';');
                            if (i > -1) {
                                final String tag = str.substring(0, i).trim();
                                final Map<String, String> params = Weighted.parseParameters(str);
                                return new Weighted<>(Locale.forLanguageTag(tag), params);
                            } else {
                                return new Weighted<>(Locale.forLanguageTag(str.trim()));
                            }
                        })
                        .sorted()
                        .map(Weighted::getObject)
                        .toList()
                        .stream();
                }
                return Stream.of(
                    Locale.forLanguageTag(text)
                );
            }).toList();
    }

    @Override
    public MediaType getMediaType() {
        return httpHeaders.getContentType().map(MediaType::valueOf).orElse(null);
    }

    @Override
    public Locale getLanguage() {
        return httpHeaders.getFirst(io.micronaut.http.HttpHeaders.CONTENT_LANGUAGE)
            .map(Locale::forLanguageTag)
            .orElse(null);
    }

    @Override
    public Map<String, Cookie> getCookies() {
        final List<String> cookieHeaders = httpHeaders.getAll(
            isResponse ? io.micronaut.http.HttpHeaders.SET_COOKIE : io.micronaut.http.HttpHeaders.COOKIE
        );
        Map<String, Cookie> cookies = CollectionUtils.newLinkedHashMap(cookieHeaders.size());
        for (String cookieHeader : cookieHeaders) {
            final List<Cookie> parsed = CookieHeaderDelegate.parseCookies(cookieHeader);
            for (Cookie cookie : parsed) {
                cookies.put(cookie.getName(), cookie);
            }
        }
        return Collections.unmodifiableMap(cookies);
    }

    @Override
    public Date getDate() {
        ZonedDateTime date = httpHeaders.getDate(io.micronaut.http.HttpHeaders.DATE);
        if (date != null) {
            return Date.from(date.toInstant());
        }
        return null;
    }

    @Override
    public int getLength() {
        if (httpHeaders.contains(io.micronaut.http.HttpHeaders.CONTENT_LENGTH)) {
            return httpHeaders.getInt(io.micronaut.http.HttpHeaders.CONTENT_LENGTH);
        }
        return -1;
    }
}
