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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseProvider;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
                String reason = response.reason();
                if (response.code() == 200 && reason.equals("Ok")) {
                    // TCK uses different case
                    reason = "OK";
                }
                return reason;
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

    @Override
    public MultivaluedMap<String, Object> getMetadata() {
        return new MetadataView(response.getAttributes(), response.getHeaders());
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        return new HeaderView(response.getAttributes(), response.getHeaders());
    }

    @Override
    public String getHeaderString(String name) {
        return response.getHeaders().getFirst(name).orElse(null);
    }

    private static abstract class HeaderOrMetadataView<V> extends AbstractMap<String, List<V>> implements MultivaluedMap<String, V> {
        private static final String ATTR_PREFIX = "jax-rs-metadata.";
        /**
         * This is the substitute for {@code null} keys to store inside {@link #attributes}. We use
         * an uppercase NULL here because normal keys are normalized to lowercase, so there can't
         * be a collision with a non-null key.
         */
        private static final String NULL_SUFFIX = "NULL";

        final MutableConvertibleValues<Object> attributes;
        final MutableHttpHeaders headers;

        private HeaderOrMetadataView(MutableConvertibleValues<Object> attributes, MutableHttpHeaders headers) {
            this.attributes = attributes;
            this.headers = headers;
        }

        /**
         * Key in {@link #attributes} to store data under.
         *
         * @param key The key in this map
         * @return The key in the {@link #attributes}
         */
        @NonNull
        static String attrKey(@Nullable String key) {
            return ATTR_PREFIX + (key == null ? NULL_SUFFIX : key);
        }

        /**
         * Check whether a given attribute belongs to this map.
         *
         * @param attr The key in {@link #attributes}
         * @return {@code true} iff the attribute is part of this map
         */
        static boolean isAttrKey(@NonNull String attr) {
            return attr.startsWith(ATTR_PREFIX);
        }

        /**
         * Reverse operation of {@link #attrKey(String)}
         */
        @Nullable
        static String unAttrKey(@NonNull String key) {
            String header = key.substring(ATTR_PREFIX.length());
            if (header.equals(NULL_SUFFIX)) {
                header = null;
            }
            return header;
        }

        @Override
        public void putSingle(String key, V value) {
            remove(key);
            add(key, value);
        }

        @Override
        public List<V> remove(Object key) {
            if (key instanceof String s) {
                List<V> prev = get(key);
                headers.remove(s);
                attributes.remove(attrKey(s));
                return prev;
            } else if (key == null) {
                List<V> prev = get(null);
                attributes.remove(attrKey(null));
                return prev;
            } else {
                return null;
            }
        }

        @Override
        public V getFirst(String key) {
            List<V> l = get(key);
            return l == null || l.isEmpty() ? null : l.get(0);
        }

        @Override
        public void addAll(String key, V... newValues) {
            addAll(key, Arrays.asList(newValues));
        }

        @Override
        public void addAll(String key, List<V> valueList) {
            for (V v : valueList) {
                add(key, v);
            }
        }

        @SuppressWarnings("Java8MapApi")
        @Override
        public void addFirst(String key, V value) {
            List<V> old = get(key);
            put(key, Stream.concat(Stream.of(value), old.stream()).toList());
        }

        @Override
        public void putAll(Map<? extends String, ? extends List<V>> m) {
            for (String s : m.keySet()) {
                put(s, m.get(s));
            }
        }

        @Override
        public List<V> put(String key, List<V> value) {
            List<V> prev = get(key);
            if (key != null) {
                headers.remove(key);
            }
            attributes.remove(attrKey(key));
            addAll(key, value);
            return prev.isEmpty() ? null : prev;
        }

        @Override
        public boolean equalsIgnoreValueOrder(MultivaluedMap<String, V> otherMap) {
            if (!keySet().equals(otherMap.keySet())) {
                return false;
            }
            for (String key : otherMap.keySet()) {
                List<V> l = get(key);
                List<V> r = otherMap.get(key);
                if (l.size() != r.size()) {
                    return false;
                }
                if (!counts(l).equals(counts(r))) {
                    return false;
                }
            }
            return true;
        }

        private static Map<Object, Integer> counts(List<?> list) {
            Map<Object, Integer> map = new HashMap<>();
            for (Object o : list) {
                map.compute(o, (k, v) -> v == null ? 1 : v + 1);
            }
            return map;
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }
    }

    private static final class MetadataView extends HeaderOrMetadataView<Object> {
        MetadataView(MutableConvertibleValues<Object> attributes, MutableHttpHeaders headers) {
            super(attributes, headers);
        }

        @Override
        public void add(String key, Object value) {
            Object existing = attributes.getValue(attrKey(key));
            if (existing instanceof List l) {
                l.add(value);
            } else {
                attributes.put(attrKey(key), new ArrayList<>(List.of(value)));
            }
            if (value instanceof String s && key != null) {
                headers.add(key, s);
            }
        }

        @Override
        public List<Object> get(Object key) {
            if (key instanceof String s) {
                return get(s);
            } else if (key == null) {
                return get(null);
            } else {
                return null;
            }
        }

        private List<Object> get(String s) {
            if (attributes.getValue(attrKey(s)) instanceof List attr) {
                return attr;
            } else if (s != null) {
                List l = headers.getAll(s);
                return l == null || l.isEmpty() ? null : l;
            } else {
                return null;
            }
        }

        @Override
        public Set<Entry<String, List<Object>>> entrySet() {
            Set<String> keys = Stream.concat(
                attributes.names().stream().filter(HeaderOrMetadataView::isAttrKey).map(HeaderOrMetadataView::unAttrKey),
                headers.names().stream()
            ).collect(Collectors.toSet());
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, List<Object>>> iterator() {
                    return new Iterator<>() {
                        Iterator<String> keyIterator = keys.iterator();

                        @Override
                        public boolean hasNext() {
                            return keyIterator.hasNext();
                        }

                        @Override
                        public Entry<String, List<Object>> next() {
                            String key = keyIterator.next();
                            return Map.entry(key, get(key));
                        }
                    };
                }

                @Override
                public int size() {
                    return keys.size();
                }
            };
        }
    }

    private static final class HeaderView extends HeaderOrMetadataView<String> {
        HeaderView(MutableConvertibleValues<Object> attributes, MutableHttpHeaders headers) {
            super(attributes, headers);
        }

        @Override
        public void add(String key, String value) {
            headers.add(key, value);
        }

        @Override
        public List<String> get(Object key) {
            return key instanceof String s ? headers.getAll(s) : null;
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
