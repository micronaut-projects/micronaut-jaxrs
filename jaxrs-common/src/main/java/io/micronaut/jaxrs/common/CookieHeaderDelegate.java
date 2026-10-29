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
import jakarta.ws.rs.core.Cookie;
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
public final class CookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<Cookie> {

    /**
     * Parse cookies from the header.
     *
     * @param cookieHeader The header
     * @return The list of cookies
     */
    @SuppressWarnings("java:S3776")
    public static List<Cookie> parseCookies(String cookieHeader) {
        JaxRsUtils.requireNonNull("cookieHeader", cookieHeader);
        try {
            List<Cookie> cookies = new ArrayList<>();

            int version = 0;
            String domain = null;
            String path = null;
            String cookieName = null;
            String cookieValue = null;

            // a delimiter in a quoted value is part of the value (RFC 2109 4.3.4)
            for (String part : JaxRsHeaderUtil.split(cookieHeader, ";,")) {
                String[] nv = part.split("=", 2);
                String name = nv.length > 0 ? nv[0].trim() : "";
                String value = nv.length > 1 ? JaxRsHeaderUtil.unquote(nv[1].trim()) : "";
                if (!name.startsWith("$")) {
                    if (cookieName != null) {
                        cookies.add(new Cookie.Builder(cookieName)
                            .value(cookieValue)
                            .path(path)
                            .domain(domain)
                            .version(version)
                            .build());
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
                cookies.add(new Cookie.Builder(cookieName)
                    .value(cookieValue)
                    .path(path)
                    .domain(domain)
                    .version(version)
                    .build());
            }
            return cookies;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid cookie header: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Cookie fromString(String value) throws IllegalArgumentException {
        return parseCookies(value).get(0);
    }

    @Override
    public String toString(Cookie value) {
        JaxRsUtils.requireNonNull("value", value);
        // the value of a Cookie request header: its path and domain are the attributes of
        // RFC 2109, which a server does not read as other cookies
        String path = value.getPath();
        String domain = value.getDomain();
        StringBuilder buf = new StringBuilder();
        if (path != null || domain != null) {
            buf.append("$Version=").append(value.getVersion()).append("; ");
        }
        buf.append(value.getName()).append('=').append(ServerCookie.quoted(value.getValue()));
        if (path != null) {
            buf.append("; $Path=").append(ServerCookie.quoted(path));
        }
        if (domain != null) {
            buf.append("; $Domain=").append(ServerCookie.quoted(domain));
        }
        return buf.toString();
    }
}
