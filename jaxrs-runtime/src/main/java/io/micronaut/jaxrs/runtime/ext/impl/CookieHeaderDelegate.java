package io.micronaut.jaxrs.runtime.ext.impl;


import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.ArgumentUtils;

import javax.ws.rs.core.Cookie;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.ArrayList;
import java.util.List;

/**
 * Forked from RESTEasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Internal
final class CookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<Cookie> {

    public Cookie fromString(String value) throws IllegalArgumentException {
        return parseCookies(value).get(0);
    }

    public String toString(Cookie value) {
        StringBuilder buf = new StringBuilder();
        ServerCookie.appendCookieValue(buf, 0, value.getName(), value.getValue(), value.getPath(), value.getDomain(), null, -1, false);
        return buf.toString();
    }

    public static List<Cookie> parseCookies(String cookieHeader) {
        ArgumentUtils.requireNonNull("cookieHeader", cookieHeader);
        try {
            List<Cookie> cookies = new ArrayList<>();

            int version = 0;
            String domain = null;
            String path = null;
            String cookieName = null;
            String cookieValue = null;

            String[] parts = cookieHeader.split("[;,]");
            for (String part : parts) {
                String[] nv = part.split("=", 2);
                String name = nv.length > 0 ? nv[0].trim() : "";
                String value = nv.length > 1 ? nv[1].trim() : "";
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1)
                    value = value.substring(1, value.length() - 1);
                if (!name.startsWith("$")) {
                    if (cookieName != null) {
                        cookies.add(new Cookie(cookieName, cookieValue, path, domain, version));
                        cookieName = cookieValue = path = domain = null;
                    }

                    cookieName = name;
                    cookieValue = value;
                } else if (name.equalsIgnoreCase("$Version")) {
                    version = Integer.parseInt(value);
                } else if (name.equalsIgnoreCase("$Path")) {
                    path = value;
                } else if (name.equalsIgnoreCase("$Domain")) {
                    domain = value;
                }
            }
            if (cookieName != null) {
                cookies.add(new Cookie(cookieName, cookieValue, path, domain, version));

            }
            return cookies;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid cookie header: " + ex.getMessage(), ex);
        }
    }
}
