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
import org.jspecify.annotations.Nullable;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * The entries of a {@link jakarta.ws.rs.core.MultivaluedMap} view of headers, read from the
 * headers: removing an entry, through the iterator, the key set or the values, setting the value
 * of an entry, and clearing write through to the headers, by the map, which throws if they are
 * immutable.
 *
 * @param <V> The type of the values
 * @author Denis Stepanov
 * @since 5.3.0
 */
@Internal
final class HeaderEntries<V> extends AbstractSet<Map.Entry<String, List<V>>> {

    private final Headers headers;
    private final Map<String, List<V>> map;

    /**
     * @param headers The headers
     * @param map     The map view of the headers, which writes to them
     */
    HeaderEntries(Headers headers, Map<String, List<V>> map) {
        this.headers = headers;
        this.map = map;
    }

    @Override
    public Iterator<Map.Entry<String, List<V>>> iterator() {
        // over a copy of the names: an entry can be removed while iterating
        Iterator<String> names = new ArrayList<>(headers.names()).iterator();
        return new Iterator<>() {
            private @Nullable String current;

            @Override
            public boolean hasNext() {
                return names.hasNext();
            }

            @Override
            public Map.Entry<String, List<V>> next() {
                if (!names.hasNext()) {
                    throw new NoSuchElementException();
                }
                String name = names.next();
                current = name;
                return new Entry(name, Objects.requireNonNullElse(map.get(name), List.of()));
            }

            @Override
            public void remove() {
                String name = current;
                if (name == null) {
                    throw new IllegalStateException("No entry to remove");
                }
                current = null;
                map.remove(name);
            }
        };
    }

    @Override
    public int size() {
        return headers.names().size();
    }

    @Override
    public void clear() {
        for (String name : new ArrayList<>(headers.names())) {
            map.remove(name);
        }
    }

    /**
     * An entry whose new value replaces the values of the header.
     */
    private final class Entry extends AbstractMap.SimpleEntry<String, List<V>> {
        Entry(String name, List<V> values) {
            super(name, values);
        }

        @Override
        public List<V> setValue(List<V> value) {
            map.put(getKey(), value);
            return super.setValue(value);
        }
    }
}
