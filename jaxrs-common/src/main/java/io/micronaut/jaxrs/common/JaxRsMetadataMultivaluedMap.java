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
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The metadata MultivaluedMap.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
final class JaxRsMetadataMultivaluedMap extends AbstractMap<String, List<Object>> implements MultivaluedMap<String, Object> {

    private final Map<String, List<Object>> map = new LinkedHashMap<>();

    private static Map<Object, Integer> counts(List<?> list) {
        Map<Object, Integer> map = new HashMap<>();
        for (Object o : list) {
            map.compute(o, (k, v) -> v == null ? 1 : v + 1);
        }
        return map;
    }

    @Override
    public void putSingle(String key, Object value) {
        map.put(key, new ArrayList<>(List.of(value)));
    }

    @Override
    public List<Object> put(String key, List<Object> value) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).addAll(value);
        return value;
    }

    @Override
    public void add(String key, Object value) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    @Override
    public List<Object> remove(Object key) {
        return map.remove(key);
    }

    @Override
    public Object getFirst(String key) {
        List<Object> l = get(key);
        return l == null || l.isEmpty() ? null : l.get(0);
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
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(0, value);
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<Object>> m) {
        for (String s : m.keySet()) {
            put(s, m.get(s));
        }
    }

    @Override
    public Set<Entry<String, List<Object>>> entrySet() {
        return map.entrySet();
    }

    @Override
    public boolean equalsIgnoreValueOrder(MultivaluedMap<String, Object> otherMap) {
        if (!keySet().equals(otherMap.keySet())) {
            return false;
        }
        for (String key : otherMap.keySet()) {
            List<Object> l = get(key);
            List<Object> r = otherMap.get(key);
            if (l.size() != r.size()) {
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
        return get(key) != null;
    }
}
