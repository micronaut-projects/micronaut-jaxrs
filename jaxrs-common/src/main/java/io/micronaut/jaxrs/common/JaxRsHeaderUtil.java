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

/**
 * A simple header util.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsHeaderUtil {

    private static final ClassValue<RuntimeDelegate.HeaderDelegate<?>> HEADER_DELEGATE_CACHE = new ClassValue<>() {
        @Override
        protected RuntimeDelegate.HeaderDelegate<?> computeValue(Class<?> type) {
            return RuntimeDelegate.getInstance().createHeaderDelegate(type);
        }
    };

    public static String headerToString(Object obj) {
        if (obj instanceof String string) {
            return string;
        }
        RuntimeDelegate.HeaderDelegate delegate = HEADER_DELEGATE_CACHE.get(obj.getClass());
        if (delegate != null) {
            return delegate.toString(obj);
        }
        return obj.toString();
    }

}
