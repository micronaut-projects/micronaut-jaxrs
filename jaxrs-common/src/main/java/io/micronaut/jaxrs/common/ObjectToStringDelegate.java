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
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.Objects;

/**
 * The implementation of {@link RuntimeDelegate.HeaderDelegate} for objects.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class ObjectToStringDelegate implements RuntimeDelegate.HeaderDelegate<Object> {

    public static final ObjectToStringDelegate INSTANCE = new ObjectToStringDelegate();

    private ObjectToStringDelegate() {
    }

    public Object fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        throw new IllegalArgumentException("Not supported");
    }

    public String toString(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        return Objects.toString(value);
    }
}
