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

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Split a header value at the delimiters outside quoted strings (RFC 9110 5.6.4), in which a
     * backslash quotes the next character.
     *
     * @param value      The value
     * @param delimiters The delimiter characters
     * @return The parts, not trimmed
     */
    public static List<String> split(String value, String delimiters) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted && c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && delimiters.indexOf(c) >= 0) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    /**
     * Remove the quotes of a quoted string and unescape its quoted-pairs (RFC 9110 5.6.4).
     *
     * @param value The value, trimmed
     * @return The value, unchanged if it is not quoted
     */
    public static String unquote(String value) {
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        StringBuilder unquoted = new StringBuilder(value.length() - 2);
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() - 1) {
                c = value.charAt(++i);
            }
            unquoted.append(c);
        }
        return unquoted.toString();
    }
}
