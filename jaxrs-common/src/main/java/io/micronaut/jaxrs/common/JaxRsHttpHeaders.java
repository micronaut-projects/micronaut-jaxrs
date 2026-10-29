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
import org.jspecify.annotations.Nullable;

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

    // the language of any language (JAX-RS, HttpHeaders#getAcceptableLanguages)
    private static final Locale WILDCARD_LOCALE = Locale.of("*");

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
    public @Nullable List<String> getRequestHeader(String name) {
        // null for a header that is absent
        return getRequestHeaders().get(name);
    }

    @Override
    public @Nullable String getHeaderString(String name) {
        List<String> all = httpHeaders.getAll(name);
        if (all.isEmpty()) {
            return null;
        }
        return String.join(",", all);
    }

    @Override
    public final boolean containsHeaderString(String name, @Nullable String valueSeparatorRegex, Predicate<String> valuePredicate) {
        return httpHeaders.getAll(name)
            .stream()
            // without a separator, each value is tested whole
            .flatMap(value -> valueSeparatorRegex == null ? Stream.of(value) : Arrays.stream(value.split(valueSeparatorRegex)))
            .map(String::trim)
            .anyMatch(valuePredicate);
    }

    @Override
    public final boolean containsHeaderString(String name, Predicate<String> valuePredicate) {
        return containsHeaderString(name, ",", valuePredicate);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
        return new JaxRsHeadersMultivaluedMap(httpHeaders);
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        List<String> accept = httpHeaders.getAll(io.micronaut.http.HttpHeaders.ACCEPT);
        List<MediaType> types = accept
            .stream()
            .flatMap(text -> Arrays.stream(text.split(",")))
            .map(String::trim)
            // RFC 9110 5.6.1: empty list elements are ignored
            .filter(element -> !element.isEmpty())
            .<Weighted<MediaType>>mapMulti((element, consumer) -> {
                MediaType type;
                try {
                    // a bare '*' (sent by java.net.HttpURLConnection) is read as */*
                    type = MediaType.valueOf(element);
                } catch (IllegalArgumentException e) {
                    // an invalid media range is ignored, as by the route negotiation
                    return;
                }
                consumer.accept(element.indexOf(';') > -1 ? new Weighted<>(type, type.getParameters()) : new Weighted<>(type));
            })
            .sorted()
            .map(Weighted::getObject)
            .toList();
        // any type is acceptable without an Accept header, or without a valid media range
        return types.isEmpty() ? List.of(MediaType.WILDCARD_TYPE) : types;
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        List<Locale> languages = acceptLanguages();
        // any language is acceptable without an Accept-Language header, or with a wildcard one
        return languages.isEmpty() ? List.of(WILDCARD_LOCALE) : languages;
    }

    private List<Locale> acceptLanguages() {
        // the values of every header line, sorted together
        return httpHeaders.getAll(io.micronaut.http.HttpHeaders.ACCEPT_LANGUAGE)
            .stream()
            .filter(text -> !text.isEmpty() && !(text.length() == 1 && text.charAt(0) == '*'))
            .flatMap(text -> Arrays.stream(text.split(",")))
            .map(String::trim)
            .filter(str -> !str.isEmpty())
            .map(str -> {
                final int i = str.indexOf(';');
                if (i > -1) {
                    final Locale locale = language(str.substring(0, i).trim());
                    return new Weighted<>(locale, Weighted.parseParameters(str));
                } else {
                    return new Weighted<>(language(str));
                }
            })
            .sorted()
            .map(Weighted::getObject)
            .toList();
    }

    private static Locale language(String tag) {
        return "*".equals(tag) ? WILDCARD_LOCALE : Locale.forLanguageTag(tag);
    }

    @Override
    public @Nullable MediaType getMediaType() {
        return httpHeaders.getContentType().map(MediaType::valueOf).orElse(null);
    }

    @Override
    public @Nullable Locale getLanguage() {
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
    public @Nullable Date getDate() {
        ZonedDateTime date = httpHeaders.getDate(io.micronaut.http.HttpHeaders.DATE);
        if (date != null) {
            return Date.from(date.toInstant());
        }
        return null;
    }

    @Override
    public int getLength() {
        Integer length = httpHeaders.getInt(io.micronaut.http.HttpHeaders.CONTENT_LENGTH);
        if (length != null) {
            return length;
        }
        return -1;
    }
}
