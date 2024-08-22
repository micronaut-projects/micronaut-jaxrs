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

import java.util.List;

/**
 * The representation of mutable headers as {@link MultivaluedMap}.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
public final class JaxRsMutableHeadersMultivaluedMap extends JaxRsHeadersMultivaluedMap implements MultivaluedMap<String, String> {

    private final MutableHeaders mutableHeaders;

    public JaxRsMutableHeadersMultivaluedMap(MutableHeaders mutableHeaders) {
        super(mutableHeaders);
        this.mutableHeaders = mutableHeaders;
    }

    @Override
    public void putSingle(String key, String value) {
        mutableHeaders.remove(key);
        mutableHeaders.add(key, value);
    }

    @Override
    public void add(String key, String value) {
        mutableHeaders.add(key, value);
    }

    @Override
    public List<String> put(String key, List<String> value) {
        value.forEach(v -> mutableHeaders.add(key, v));
        return value;
    }
}
