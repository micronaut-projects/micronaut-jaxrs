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
package io.micronaut.jaxrs.runtime.ext.impl;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.ArgumentUtils;

import javax.ws.rs.core.CacheControl;
import javax.ws.rs.ext.RuntimeDelegate;

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

    @Override
    @SuppressWarnings("java:S3776")
    public CacheControl fromString(String value) throws IllegalArgumentException {
        ArgumentUtils.requireNonNull("value", value);
        CacheControl result = new CacheControl();
        result.setNoTransform(false);

        String[] directives = value.split(",");
        for (String directive : directives) {
            directive = directive.trim();

            String[] nv = directive.split("=");
            String name = nv[0].trim();
            String val = null;
            if (nv.length > 1) {
                val = nv[1].trim();
                if (val.startsWith("\"")) {
                    val = val.substring(1);
                }
                if (val.endsWith("\"")) {
                    val = val.substring(0, val.length() - 1);
                }
            }

            String lowercase = name.toLowerCase();
            if (NO_CACHE.equals(lowercase)) {
                result.setNoCache(true);
                if (val != null && !"".equals(val)) {
                    result.getNoCacheFields().add(val);
                }
            } else if (PRIVATE.equals(lowercase)) {
                result.setPrivate(true);
                if (val != null && !"".equals(val)) {
                    result.getPrivateFields().add(val);
                }
            } else if ("no-store".equals(lowercase)) {
                result.setNoStore(true);
            } else if ("max-age".equals(lowercase)) {
                if (val == null) {
                    throw new IllegalArgumentException("CacheControl max-age header does not have a value: " + value);
                }
                result.setMaxAge(Integer.parseInt(val));
            } else if ("s-maxage".equals(lowercase)) {
                if (val == null) {
                    throw new IllegalArgumentException("CacheControl s-max-age header does not have a value: " + value);
                }
                result.setSMaxAge(Integer.parseInt(val));
            } else if ("no-transform".equals(lowercase)) {
                result.setNoTransform(true);
            } else if ("must-revalidate".equals(lowercase)) {
                result.setMustRevalidate(true);
            } else if ("proxy-revalidate".equals(lowercase)) {
                result.setProxyRevalidate(true);
            } else {
                if (val == null) {
                    val = "";
                }
                result.getCacheExtension().put(name, val);
            }
        }
        return result;
    }

    @Override
    @SuppressWarnings("java:S3776")
    public String toString(CacheControl value) {
        ArgumentUtils.requireNonNull("value", value);
        StringBuilder buffer = new StringBuilder();
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
            if (val != null && !"".equals(val)) {
                buffer.append("=\"").append(val).append("\"");
            }
        }
        return buffer.toString();
    }

    private static StringBuilder addDirective(String directive, StringBuilder buffer) {
        if (buffer.length() > 0) {
            buffer.append(", ");
        }
        buffer.append(directive);
        return buffer;
    }
}
