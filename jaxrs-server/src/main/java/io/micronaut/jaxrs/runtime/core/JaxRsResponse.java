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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseProvider;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static jakarta.ws.rs.ext.RuntimeDelegate.getInstance;

/**
 * Adapter for JAX-RS and final Micronaut response.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
class JaxRsResponse extends Response implements HttpResponseProvider {

    private final MutableHttpResponse<Object> response = HttpResponse.ok();
    private boolean closed;

    /**
     * @return The Micronaut response object
     */
    @Override
    public MutableHttpResponse<Object> getResponse() {
        return response;
    }

    @Override
    public int getStatus() {
        return response.code();
    }

    @Override
    public StatusType getStatusInfo() {
        return new StatusType() {
            @Override
            public int getStatusCode() {
                return getStatus();
            }

            @Override
            public Status.Family getFamily() {
                return Status.Family.familyOf(getStatusCode());
            }

            @Override
            public String getReasonPhrase() {
                return response.reason();
            }
        };
    }

    @Override
    public Object getEntity() {
        checkOpen();
        return response.body();
    }

    @Override
    public <T> T readEntity(Class<T> entityType) {
        return response.getBody(entityType).orElse(null);
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType) {
        return null;
    }

    @Override
    public <T> T readEntity(Class<T> entityType, Annotation[] annotations) {
        return readEntity(entityType);
    }

    @Override
    public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) {
        return readEntity(entityType);
    }

    @Override
    public boolean hasEntity() {
        checkOpen();
        return response.getBody().isPresent();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Response closed");
        }
    }

    @Override
    public boolean bufferEntity() {
        checkOpen();
        return false;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public MediaType getMediaType() {
        return response.getContentType().map(mt -> MediaType.valueOf(mt.toString())).orElse(null);
    }

    @Override
    public Locale getLanguage() {
        return response.getLocale().orElse(null);
    }

    @Override
    public int getLength() {
        return (int) response.getContentLength();
    }

    @Override
    public Set<String> getAllowedMethods() {
        final List<String> allow = response.getHeaders().getAll(io.micronaut.http.HttpHeaders.ALLOW);
        return new HashSet<>(allow);
    }

    @Override
    @Nullable
    public Map<String, NewCookie> getCookies() {
        throw new UnsupportedOperationException();
    }

    @Override
    public EntityTag getEntityTag() {
        return response.getHeaders()
            .getFirst(io.micronaut.http.HttpHeaders.ETAG)
            .map(entityTag -> getInstance().createHeaderDelegate(EntityTag.class).fromString(entityTag))
            .orElse(null);
    }

    @Override
    public Date getDate() {
        ZonedDateTime date = response.getHeaders().getDate(HttpHeaders.DATE);
        if (date != null) {
            return Date.from(date.toInstant());
        }
        return null;
    }

    @Override
    public Date getLastModified() {
        return null;
    }

    @Override
    public URI getLocation() {
        return response.getHeaders()
                    .getFirst(io.micronaut.http.HttpHeaders.LOCATION)
                    .map(URI::create).orElse(null);
    }

    @Override
    public Set<Link> getLinks() {
        // TODO
        return Collections.emptySet();
    }

    @Override
    public boolean hasLink(String relation) {
        // TODO
        return false;
    }

    @Override
    public Link getLink(String relation) {
        // TODO
        return null;
    }

    @Override
    public Link.Builder getLinkBuilder(String relation) {
        // TODO
        return null;
    }

    @SuppressWarnings({"rawtypes", "UnnecessaryLocalVariable", "unchecked"})
    @Override
    public MultivaluedMap<String, Object> getMetadata() {
        MultivaluedMap map = getStringHeaders();
        return map;
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        return new HeaderView(response.getHeaders());
    }

    @Override
    public String getHeaderString(String name) {
        return response.getHeaders().getFirst(name).orElse(null);
    }

    private static final class HeaderView extends AbstractMap<String, List<String>> implements MultivaluedMap<String, String> {
        private final MutableHttpHeaders headers;

        HeaderView(MutableHttpHeaders headers) {
            this.headers = headers;
        }

        @Override
        public void putSingle(String key, String value) {
            headers.remove(key);
            headers.add(key, value);
        }

        @Override
        public void add(String key, String value) {
            headers.add(key, value);
        }

        @Override
        public String getFirst(String key) {
            return headers.getFirst(key).orElse(null);
        }

        @Override
        public void addAll(String key, String... newValues) {
            addAll(key, Arrays.asList(newValues));
        }

        @Override
        public void addAll(String key, List<String> valueList) {
            for (String v : valueList) {
                add(key, v);
            }
        }

        @Override
        public void addFirst(String key, String value) {
            add(key, value);
        }

        @Override
        public boolean equalsIgnoreValueOrder(MultivaluedMap<String, String> otherMap) {
            if (!keySet().equals(otherMap.keySet())) {
                return false;
            }
            for (String key : otherMap.keySet()) {
                List<String> l = get(key);
                List<String> r = otherMap.get(key);
                if (l.size() != r.size()) {
                    return false;
                }
                l = l.stream().sorted().toList();
                r = r.stream().sorted().toList();
                if (!l.equals(r)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public List<String> put(String key, List<String> value) {
            List<String> prev = headers.getAll(key);
            headers.remove(key);
            addAll(key, value);
            return prev.isEmpty() ? null : prev;
        }

        @Override
        public void putAll(Map<? extends String, ? extends List<String>> m) {
            for (String s : m.keySet()) {
                put(s, m.get(s));
            }
        }

        @Override
        public boolean containsKey(Object key) {
            // case insensitive
            return key instanceof String s && headers.contains(s);
        }

        @Override
        public List<String> get(Object key) {
            // case insensitive
            return key instanceof String s ? headers.getAll(s) : null;
        }

        @Override
        public List<String> remove(Object key) {
            // case insensitive
            List<String> prev = get(key);
            if (key instanceof String s) {
                headers.remove(s);
            }
            return prev;
        }

        @Override
        public Set<Entry<String, List<String>>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, List<String>>> iterator() {
                    return headers.iterator();
                }

                @Override
                public int size() {
                    return headers.names().size();
                }
            };
        }
    }
}
