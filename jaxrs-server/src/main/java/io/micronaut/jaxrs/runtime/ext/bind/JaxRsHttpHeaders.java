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
package io.micronaut.jaxrs.runtime.ext.bind;

import io.micronaut.core.annotation.Internal;
import io.micronaut.jaxrs.runtime.core.Weighted;
import io.micronaut.jaxrs.runtime.ext.impl.CookieHeaderDelegate;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapter class for JAR-RS headers.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public class JaxRsHttpHeaders implements HttpHeaders {

    private final io.micronaut.http.HttpHeaders httpHeaders;

    /**
     * Default constructor.
     * @param httpHeaders The Micronaut headers
     */
    public JaxRsHttpHeaders(io.micronaut.http.HttpHeaders httpHeaders) {
        this.httpHeaders = httpHeaders;
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
        } else {
            return String.join(",", all);
        }
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>(getLength());
        httpHeaders.forEach(map::put);
        return map;
    }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        final List<MediaType> mediaTypes = httpHeaders.findFirst(io.micronaut.http.HttpHeaders.ACCEPT)
                .map(text -> {
                    int len = text.length();
                    if (len == 0) {
                        return Collections.singletonList(MediaType.valueOf(io.micronaut.http.MediaType.ALL));
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
                                .toList();
                    }
                    return Collections.singletonList(
                            MediaType.valueOf(text)
                    );
                }).orElse(Collections.emptyList());
        return Collections.unmodifiableList(mediaTypes);
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        final List<Locale> locales = httpHeaders.findFirst(io.micronaut.http.HttpHeaders.ACCEPT_LANGUAGE)
                .map(text -> {
                    int len = text.length();
                    if (len == 0 || (len == 1 && text.charAt(0) == '*')) {
                        return Collections.<Locale>emptyList();
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
                                .toList();
                    }
                    return Collections.singletonList(
                            Locale.forLanguageTag(text)
                    );
                }).orElse(Collections.emptyList());
        return Collections.unmodifiableList(locales);
    }

    @Override
    public MediaType getMediaType() {
        return httpHeaders.getContentType().map(MediaType::valueOf).orElse(null);
    }

    @Override
    public Locale getLanguage() {
        return httpHeaders.getFirst(io.micronaut.http.HttpHeaders.CONTENT_LANGUAGE)
                .map(Locale::new)
                .orElse(null);
    }

    @Override
    public Map<String, Cookie> getCookies() {
        final List<String> cookieHeaders = httpHeaders.getAll(io.micronaut.http.HttpHeaders.SET_COOKIE);
        Map<String, Cookie> cookies = new LinkedHashMap<>(cookieHeaders.size());
        for (String cookieHeader : cookieHeaders) {
            final List<Cookie> parsed
                    = CookieHeaderDelegate.parseCookies(cookieHeader);
            for (Cookie cookie : parsed) {
                cookies.put(cookie.getName(), cookie);
            }
        }
        return Collections.unmodifiableMap(cookies);
    }

    @Override
    public Date getDate() {
        return null;
    }

    @Override
    public int getLength() {
        return httpHeaders.names().size();
    }
}
