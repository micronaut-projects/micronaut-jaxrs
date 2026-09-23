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
import io.micronaut.core.type.MutableHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.jspecify.annotations.Nullable;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The metadata of a {@link jakarta.ws.rs.core.Response}: the headers as the objects they were given
 * as, e.g. a {@code Date}, written to the headers of the response as strings by their header
 * delegates. Like any {@link MultivaluedMap}, it takes a {@code null} key and empty value lists,
 * which are not headers. A header changed through the response is read again from it.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public final class JaxRsResponseMetadata extends AbstractMap<String, List<Object>> implements MultivaluedMap<String, Object> {

    private final MutableHeaders headers;

    private final Map<@Nullable String, List<Object>> objects;

    private final Map<String, List<String>> written;

    /**
     * @param headers The headers of the response
     * @param state   The objects of the headers, kept by the response
     */
    public JaxRsResponseMetadata(MutableHeaders headers, State state) {
        this.headers = headers;
        this.objects = state.objects;
        this.written = state.written;
    }

    @Override
    @SuppressWarnings("NullAway") // a MultivaluedMap takes a null key
    public List<Object> get(@Nullable Object key) {
        String name = (String) key;
        List<Object> values = objects.get(name);
        if (name == null) {
            return values;
        }
        List<String> current = headers.getAll(name);
        if (values == null || !current.equals(written.get(name))) {
            if (current.isEmpty()) {
                List<String> previous = written.get(name);
                if (previous != null && !previous.isEmpty()) {
                    // removed through the response
                    objects.remove(name);
                    written.remove(name);
                    return null;
                }
                return values == null ? null : new HeaderValues(name, values);
            }
            // changed through the response
            values = new ArrayList<>(current);
            objects.put(name, values);
            written.put(name, current);
        }
        return new HeaderValues(name, values);
    }

    @Override
    public boolean containsKey(@Nullable Object key) {
        return objects.containsKey(key) || (key instanceof String name && headers.contains(name));
    }

    @Override
    public @Nullable Object getFirst(@Nullable String key) {
        List<Object> values = get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @Override
    public void putSingle(@Nullable String key, @Nullable Object value) {
        List<Object> values = new ArrayList<>(1);
        values.add(value);
        objects.put(key, values);
        write(key);
    }

    @Override
    public void add(@Nullable String key, @Nullable Object value) {
        values(key).add(value);
        write(key);
    }

    @Override
    @SafeVarargs
    public final void addAll(@Nullable String key, Object... newValues) {
        addAll(key, Arrays.asList(Objects.requireNonNull(newValues, "newValues")));
    }

    @Override
    public void addAll(@Nullable String key, List<Object> valueList) {
        Objects.requireNonNull(valueList, "valueList");
        if (valueList.isEmpty()) {
            return;
        }
        values(key).addAll(valueList);
        write(key);
    }

    @Override
    public void addFirst(@Nullable String key, @Nullable Object value) {
        values(key).add(0, value);
        write(key);
    }

    @Override
    public @Nullable List<Object> put(@Nullable String key, List<Object> value) {
        List<Object> previous = get(key);
        objects.put(key, new ArrayList<>(value));
        write(key);
        return previous;
    }

    @Override
    public @Nullable List<Object> remove(@Nullable Object key) {
        List<Object> previous = get(key);
        objects.remove(key);
        if (key instanceof String name) {
            headers.remove(name);
            written.remove(name);
        }
        return previous;
    }

    @Override
    public void clear() {
        for (String name : headers.names()) {
            headers.remove(name);
        }
        objects.clear();
        written.clear();
    }

    @Override
    public boolean equalsIgnoreValueOrder(MultivaluedMap<String, Object> otherMap) {
        if (!keySet().equals(otherMap.keySet())) {
            return false;
        }
        for (Entry<String, List<Object>> entry : entrySet()) {
            List<Object> other = otherMap.get(entry.getKey());
            if (other == null || !counts(entry.getValue()).equals(counts(other))) {
                return false;
            }
        }
        return true;
    }

    @Override
    @SuppressWarnings("NullAway") // a MultivaluedMap takes a null key
    public Set<Entry<String, List<Object>>> entrySet() {
        Map<@Nullable String, List<Object>> all = new LinkedHashMap<>();
        for (String name : headers.names()) {
            all.put(name, get(name));
        }
        objects.forEach(all::putIfAbsent);
        return (Set) all.entrySet();
    }

    private List<Object> values(@Nullable String key) {
        get(key);
        return objects.computeIfAbsent(key, k -> new ArrayList<>());
    }

    /**
     * Write the values of a header to the response.
     */
    private void write(@Nullable String key) {
        if (key == null) {
            return;
        }
        headers.remove(key);
        List<Object> values = objects.get(key);
        if (values != null) {
            for (String value : strings(values)) {
                headers.add(key, value);
            }
        }
        written.put(key, headers.getAll(key));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(List<Object> values) {
        List<String> strings = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value != null) {
                RuntimeDelegate.HeaderDelegate<Object> delegate = RuntimeDelegate.getInstance().createHeaderDelegate((Class<Object>) value.getClass());
                strings.add(delegate.toString(value));
            }
        }
        return strings;
    }

    private static Map<Object, Integer> counts(List<?> list) {
        Map<Object, Integer> counts = new HashMap<>();
        for (Object o : list) {
            counts.merge(o, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * The objects of the headers of a response, and the header values they were written as: the
     * objects are the metadata as long as the headers keep these values.
     */
    public static final class State {
        private final Map<@Nullable String, List<Object>> objects = new HashMap<>();
        private final Map<String, List<String>> written = new HashMap<>();

        /**
         * @return A copy, for another response
         */
        public State copy() {
            State copy = new State();
            objects.forEach((name, values) -> copy.objects.put(name, new ArrayList<>(values)));
            copy.written.putAll(written);
            return copy;
        }

        /**
         * Record one more object a header was written from, e.g. a {@code NewCookie}.
         *
         * @param headers The headers the value was written to
         * @param name    The name of the header
         * @param value   The object
         */
        public void add(MutableHeaders headers, String name, Object value) {
            objects.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
            written.put(name, headers.getAll(name));
        }

        /**
         * Record the object a header was written from, e.g. a {@code Date}.
         *
         * @param headers The headers the value was written to
         * @param name    The name of the header
         * @param value   The object, {@code null} to forget it
         */
        public void set(MutableHeaders headers, String name, @Nullable Object value) {
            if (value == null) {
                objects.remove(name);
                written.remove(name);
            } else {
                List<Object> values = new ArrayList<>(1);
                values.add(value);
                objects.put(name, values);
                written.put(name, headers.getAll(name));
            }
        }
    }

    /**
     * The values of a header, whose changes are written to the headers of the response.
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
            write(name);
            return previous;
        }

        @Override
        public void add(int index, Object element) {
            values.add(index, element);
            write(name);
        }

        @Override
        public Object remove(int index) {
            Object previous = values.remove(index);
            write(name);
            return previous;
        }
    }
}
