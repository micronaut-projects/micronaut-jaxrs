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
public final class CookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<Cookie> {

    @Override
    public Cookie fromString(String value) throws IllegalArgumentException {
        return parseCookies(value).get(0);
    }

    @Override
    public String toString(Cookie value) {
        StringBuilder buf = new StringBuilder();
        ServerCookie.appendCookieValue(buf, 0, value.getName(), value.getValue(), value.getPath(), value.getDomain(), null, -1, false);
        return buf.toString();
    }

    /**
     * Parse cookies from the header.
     * @param cookieHeader The header
     * @return The list of cookies
     */
    @SuppressWarnings("java:S3776")
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
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!name.startsWith("$")) {
                    if (cookieName != null) {
                        cookies.add(new Cookie(cookieName, cookieValue, path, domain, version));
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
