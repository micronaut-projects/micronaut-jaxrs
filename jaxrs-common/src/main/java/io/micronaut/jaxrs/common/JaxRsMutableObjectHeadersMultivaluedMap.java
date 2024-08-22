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
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.List;
import java.util.Objects;

/**
 * The representation of mutable headers as {@link MultivaluedMap}.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
public final class JaxRsMutableObjectHeadersMultivaluedMap extends JaxRsObjectHeadersMultivaluedMap {

    private final MutableHeaders headers;

    public JaxRsMutableObjectHeadersMultivaluedMap(MutableHeaders headers) {
        super(headers);
        this.headers = headers;
    }

    @Override
    public void putSingle(String key, Object value) {
        remove(key);
        add(key, value);
    }

    @Override
    public void add(String key, Object value) {
        Objects.requireNonNull(value);
        RuntimeDelegate.HeaderDelegate<Object> headerDelegate = RuntimeDelegate.getInstance().createHeaderDelegate((Class<Object>) value.getClass());
        headers.add(key, headerDelegate.toString(value));
    }

    @Override
    public List<Object> remove(Object key) {
        if (key instanceof String s) {
            List<Object> prev = get(key);
            headers.remove(s);
            return prev;
        } else if (key == null) {
            return get(null);
        } else {
            return null;
        }
    }

    @Override
    public List<Object> put(String key, List<Object> value) {
        List<Object> prev = get(key);
        if (key != null) {
            headers.remove(key);
        }
        addAll(key, value);
        return prev.isEmpty() ? null : prev;
    }

}
