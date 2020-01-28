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

    @Override
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
            if ("no-cache".equals(lowercase)) {
                result.setNoCache(true);
                if (val != null && !"".equals(val)) {
                    result.getNoCacheFields().add(val);
                }
            } else if ("private".equals(lowercase)) {
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
                result.setMaxAge(Integer.valueOf(val));
            } else if ("s-maxage".equals(lowercase)) {
                if (val == null) {
                    throw new IllegalArgumentException("CacheControl s-max-age header does not have a value: " + value);
                }
                result.setSMaxAge(Integer.valueOf(val));
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
    public String toString(CacheControl value) {
        ArgumentUtils.requireNonNull("value", value);
        StringBuilder buffer = new StringBuilder();
        if (value.isNoCache()) {
            List<String> fields = value.getNoCacheFields();
            if (fields.size() < 1) {
                addDirective("no-cache", buffer);
            } else {
                for (String field : value.getNoCacheFields()) {
                    addDirective("no-cache", buffer).append("=\"").append(field).append("\"");
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
            if (fields.size() < 1) {
                addDirective("private", buffer);
            } else {
                for (String field : value.getPrivateFields()) {
                    addDirective("private", buffer).append("=\"").append(field).append("\"");
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
