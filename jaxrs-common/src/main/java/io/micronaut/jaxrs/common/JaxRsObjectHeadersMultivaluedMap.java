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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Headers;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jspecify.annotations.Nullable;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The representation of mutable headers as {@link MultivaluedMap}.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
public class JaxRsObjectHeadersMultivaluedMap extends AbstractMap<String, List<Object>> implements MultivaluedMap<String, Object> {

    private final Headers headers;

    JaxRsObjectHeadersMultivaluedMap(Headers headers) {
        this.headers = headers;
    }

    private static Map<Object, Integer> counts(List<?> list) {
        Map<Object, Integer> map = new HashMap<>();
        for (Object o : list) {
            map.compute(o, (k, v) -> v == null ? 1 : v + 1);
        }
        return map;
    }

    @Override
    public void putSingle(String key, Object value) {
        throw new IllegalStateException("Not supported");
    }

    @Override
    public void add(String key, Object value) {
        throw new IllegalStateException("Not supported");
    }

    @Override
    public @Nullable List<Object> remove(Object key) {
        throw new IllegalStateException("Not supported");
    }

    @Override
    public @Nullable List<Object> put(String key, List<Object> value) {
        throw new IllegalStateException("Not supported");
    }

    @Override
    public @Nullable Object getFirst(String key) {
        List<Object> l = get(key);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    @Override
    public @Nullable List<Object> get(Object key) {
        // null for an absent header, like any map (Map.get), e.g. for putIfAbsent
        if (key == null || !headers.contains(key.toString())) {
            return null;
        }
        // a view of the values: a change writes the values through, e.g. clear() removes the header
        return new HeaderValues(key.toString(), new ArrayList<>(headers.getAll(key.toString())));
    }

    @SafeVarargs
    @Override
    public final void addAll(String key, Object... newValues) {
        addAll(key, Arrays.asList(newValues));
    }

    @Override
    public void addAll(String key, List<Object> valueList) {
        for (Object v : valueList) {
            add(key, v);
        }
    }

    @Override
    public void addFirst(String key, Object value) {
        List<Object> old = get(key);
        put(key, old == null ? List.of(value) : Stream.concat(Stream.of(value), old.stream()).toList());
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<Object>> m) {
        for (String s : m.keySet()) {
            put(s, m.get(s));
        }
    }

    @Override
    public Set<Entry<String, List<Object>>> entrySet() {
        // a view of the headers: a removal, a new value of an entry and clear() write through
        return new HeaderEntries<>(headers, this);
    }

    @Override
    public boolean equalsIgnoreValueOrder(MultivaluedMap<String, Object> otherMap) {
        if (!keySet().equals(otherMap.keySet())) {
            return false;
        }
        for (String key : otherMap.keySet()) {
            List<Object> l = get(key);
            List<Object> r = otherMap.get(key);
            if (l == null || r == null || l.size() != r.size()) {
                return false;
            }
            if (!counts(l).equals(counts(r))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean containsKey(Object key) {
        return headers.contains(key.toString());
    }

    /**
     * The values of a header, which write a change through to the headers.
     */
    private final class HeaderValues extends AbstractList<Object> {
        private final String name;
        private final List<Object> values;

        HeaderValues(String name, List<Object> values) {
            this.name = name;
            this.values = values;
        }

        @Override
        public Object get(int index) {
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public Object set(int index, Object element) {
            Object previous = values.set(index, element);
            write();
            return previous;
        }

        @Override
        public void add(int index, Object element) {
            values.add(index, element);
            write();
        }

        @Override
        public Object remove(int index) {
            Object previous = values.remove(index);
            write();
            return previous;
        }

        @Override
        protected void removeRange(int fromIndex, int toIndex) {
            values.subList(fromIndex, toIndex).clear();
            write();
        }

        private void write() {
            put(name, new ArrayList<>(values));
        }
    }

}
