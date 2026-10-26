/*
 * Copyright 2017-2020 original authors
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
import io.micronaut.core.util.ArgumentUtils;
import org.jspecify.annotations.Nullable;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.ArrayList;
import java.util.List;

/**
 * Forked from RESTEasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Internal
class CacheControlDelegate implements RuntimeDelegate.HeaderDelegate<CacheControl> {
    public static final CacheControlDelegate INSTANCE = new CacheControlDelegate();

    public static final String NO_CACHE = "no-cache";

    public static final String PRIVATE = "private";

    private static StringBuilder addDirective(String directive, StringBuilder buffer) {
        if (!buffer.isEmpty()) {
            buffer.append(", ");
        }
        buffer.append(directive);
        return buffer;
    }

    @Override
    @SuppressWarnings("java:S3776")
    public CacheControl fromString(String value) throws IllegalArgumentException {
        JaxRsUtils.requireNonNull("value", value);
        CacheControl result = new CacheControl();
        result.setNoTransform(false);

        for (String directive : directives(value)) {
            int eq = directive.indexOf('=');
            String name = (eq < 0 ? directive : directive.substring(0, eq)).trim();
            String val = eq < 0 ? null : unquote(directive.substring(eq + 1).trim());

            String lowercase = name.toLowerCase();
            switch (lowercase) {
                case NO_CACHE -> {
                    result.setNoCache(true);
                    addFields(val, result.getNoCacheFields());
                }
                case PRIVATE -> {
                    result.setPrivate(true);
                    addFields(val, result.getPrivateFields());
                }
                case "no-store" -> result.setNoStore(true);
                case "max-age" -> {
                    if (val == null) {
                        throw new IllegalArgumentException("CacheControl max-age header does not have a value: " + value);
                    }
                    result.setMaxAge(Integer.parseInt(val));
                }
                case "s-maxage" -> {
                    if (val == null) {
                        throw new IllegalArgumentException("CacheControl s-max-age header does not have a value: " + value);
                    }
                    result.setSMaxAge(Integer.parseInt(val));
                }
                case "no-transform" -> result.setNoTransform(true);
                case "must-revalidate" -> result.setMustRevalidate(true);
                case "proxy-revalidate" -> result.setProxyRevalidate(true);
                default -> {
                    if (val == null) {
                        val = "";
                    }
                    result.getCacheExtension().put(name, val);
                }
            }
        }
        return result;
    }

    /**
     * Splits the directives at the commas outside of quoted strings: the field names of a quoted
     * {@code private} or {@code no-cache} value are separated by commas as well (RFC 9111 5.2.2.7).
     */
    private static List<String> directives(String value) {
        var directives = new ArrayList<String>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted && c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                addDirective(value.substring(start, i), directives);
                start = i + 1;
            }
        }
        addDirective(value.substring(start), directives);
        return directives;
    }

    private static void addDirective(String directive, List<String> directives) {
        String trimmed = directive.trim();
        if (!trimmed.isEmpty()) {
            directives.add(trimmed);
        }
    }

    private static String unquote(String value) {
        if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
            return value;
        }
        var buffer = new StringBuilder(value.length());
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() - 1) {
                c = value.charAt(++i);
            }
            buffer.append(c);
        }
        return buffer.toString();
    }

    private static void addFields(@Nullable String value, List<String> fields) {
        if (value != null) {
            for (String field : value.split(",")) {
                String trimmed = field.trim();
                if (!trimmed.isEmpty()) {
                    fields.add(trimmed);
                }
            }
        }
    }

    @Override
    @SuppressWarnings("java:S3776")
    public String toString(CacheControl value) {
        ArgumentUtils.requireNonNull("value", value);
        var buffer = new StringBuilder();
        if (value.isNoCache()) {
            List<String> fields = value.getNoCacheFields();
            if (fields.isEmpty()) {
                addDirective(NO_CACHE, buffer);
            } else {
                for (String field : value.getNoCacheFields()) {
                    addDirective(NO_CACHE, buffer).append("=\"").append(field).append("\"");
                }
            }
        }
        if (value.isMustRevalidate()) {
            addDirective("must-revalidate", buffer);
        }
        if (value.isNoTransform()) {
            addDirective("no-transform", buffer);
        }
        if (value.isNoStore()) {
            addDirective("no-store", buffer);
        }
        if (value.isProxyRevalidate()) {
            addDirective("proxy-revalidate", buffer);
        }
        if (value.getSMaxAge() > -1) {
            addDirective("s-maxage", buffer).append("=").append(value.getSMaxAge());
        }
        if (value.getMaxAge() > -1) {
            addDirective("max-age", buffer).append("=").append(value.getMaxAge());
        }
        if (value.isPrivate()) {
            List<String> fields = value.getPrivateFields();
            if (fields.isEmpty()) {
                addDirective(PRIVATE, buffer);
            } else {
                for (String field : value.getPrivateFields()) {
                    addDirective(PRIVATE, buffer).append("=\"").append(field).append("\"");
                }
            }
        }
        for (String key : value.getCacheExtension().keySet()) {
            String val = value.getCacheExtension().get(key);
            addDirective(key, buffer);
            if (val != null && !val.isEmpty()) {
                buffer.append("=\"").append(val).append("\"");
            }
        }
        return buffer.toString();
    }
}
