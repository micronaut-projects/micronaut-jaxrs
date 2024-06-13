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
import io.micronaut.core.type.Headers;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The representation of headers as {@link MultivaluedMap}.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
public class JaxRsHeadersMultivaluedMap extends AbstractMap<String, List<String>> implements MultivaluedMap<String, String> {

    private final Headers headers;

    public JaxRsHeadersMultivaluedMap(Headers headers) {
        this.headers = headers;
    }

    @Override
    public void putSingle(String key, String value) {
        throw new IllegalStateException("Not supported");
    }

    @Override
    public void add(String key, String value) {
        throw new IllegalStateException("Not supported");
    }

    @Override
    public List<String> remove(Object key) {
        throw new IllegalStateException("Not supported");
    }

    @Override
    public final String getFirst(String key) {
        List<String> l = get(key);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    @SafeVarargs
    @Override
    public final void addAll(String key, String... newValues) {
        addAll(key, Arrays.asList(newValues));
    }

    @Override
    public final void addAll(String key, List<String> valueList) {
        for (String v : valueList) {
            add(key, v);
        }
    }

    @SuppressWarnings("Java8MapApi")
    @Override
    public final void addFirst(String key, String value) {
        List<String> old = get(key);
        put(key, Stream.concat(Stream.of(value), old.stream()).toList());
    }

    @Override
    public final void putAll(Map<? extends String, ? extends List<String>> m) {
        for (String s : m.keySet()) {
            put(s, m.get(s));
        }
    }

    @Override
    public final Set<Entry<String, List<String>>> entrySet() {
        return headers.asMap().entrySet();
    }

    @Override
    public List<String> put(String key, List<String> value) {
        throw new IllegalStateException("Not supported");
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
