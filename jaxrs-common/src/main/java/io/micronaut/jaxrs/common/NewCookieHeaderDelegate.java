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
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.ext.RuntimeDelegate;

import org.jspecify.annotations.Nullable;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Forked from RESTEasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Internal
class NewCookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate<Object> {
    private static final String OLD_COOKIE_PATTERN = "EEE, dd-MMM-yyyy HH:mm:ss z";
    // RFC 1123 with a two-digit day, like Netty
    private static final DateTimeFormatter EXPIRES_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    @Override
    public Object fromString(String newCookie) throws IllegalArgumentException {
        JaxRsUtils.requireNonNull("newCookie", newCookie);
        String cookieName = null;
        String cookieValue = null;
        String comment = null;
        String domain = null;
        int maxAge = NewCookie.DEFAULT_MAX_AGE;
        String path = null;
        boolean secure = false;
        int version = Cookie.DEFAULT_VERSION;
        boolean httpOnly = false;
        Date expiry = null;
        NewCookie.SameSite sameSite = null;

        // the first name=value pair is the cookie, the others are its attributes
        // a semicolon in a quoted value is part of the value (RFC 2109 4.2.2)
        for (String part : JaxRsHeaderUtil.split(newCookie, ";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String name = eq < 0 ? trimmed : trimmed.substring(0, eq).trim();
            String value = eq < 0 ? null : JaxRsHeaderUtil.unquote(trimmed.substring(eq + 1).trim());
            if (cookieName == null) {
                cookieName = name;
                cookieValue = value;
            } else if (name.equalsIgnoreCase("Comment")) {
                comment = value;
            } else if (name.equalsIgnoreCase("Domain")) {
                domain = value;
            } else if (name.equalsIgnoreCase("Max-Age") && value != null) {
                maxAge = Integer.parseInt(value);
            } else if (name.equalsIgnoreCase("Path")) {
                path = value;
            } else if (name.equalsIgnoreCase("Secure")) {
                secure = true;
            } else if (name.equalsIgnoreCase("Version") && value != null) {
                version = Integer.parseInt(value);
            } else if (name.equalsIgnoreCase("HttpOnly")) {
                httpOnly = true;
            } else if (name.equalsIgnoreCase("Expires") && value != null) {
                expiry = parseExpires(value);
            } else if (name.equalsIgnoreCase("SameSite") && value != null) {
                sameSite = sameSite(value);
            }
            // other attributes, e.g. Partitioned, are not part of NewCookie
        }
        if (cookieName == null) {
            throw new IllegalArgumentException("Invalid cookie: " + newCookie);
        }
        if (cookieValue == null) {
            cookieValue = "";
        }

        return new NewCookie.Builder(cookieName)
            .value(cookieValue)
            .path(path)
            .domain(domain)
            .version(version)
            .comment(comment)
            .maxAge(maxAge)
            .expiry(expiry)
            .secure(secure)
            .httpOnly(httpOnly)
            .sameSite(sameSite)
            .build();
    }

    @Override
    public String toString(Object value) {
        ArgumentUtils.requireNonNull("value", value);
        NewCookie cookie = (NewCookie) value;
        StringBuilder b = new StringBuilder();

        b.append(cookie.getName()).append('=');

        if (cookie.getValue() != null) {
            quote(b, cookie.getValue());
        }

        b.append(";").append("Version=").append(cookie.getVersion());

        if (cookie.getComment() != null) {
            b.append(";Comment=");
            quote(b, cookie.getComment());
        }
        if (cookie.getDomain() != null) {
            b.append(";Domain=");
            quote(b, cookie.getDomain());
        }
        if (cookie.getPath() != null) {
            b.append(";Path=");
            b.append(cookie.getPath());
        }
        if (cookie.getMaxAge() != -1) {
            b.append(";Max-Age=");
            b.append(cookie.getMaxAge());
        }
        if (cookie.getExpiry() != null) {
            b.append(";Expires=");
            b.append(EXPIRES_FORMAT.format(cookie.getExpiry().toInstant().atZone(ZoneOffset.UTC)));
        }
        if (cookie.isSecure()) {
            b.append(";Secure");
        }
        if (cookie.isHttpOnly()) {
            b.append(";HttpOnly");
        }
        if (cookie.getSameSite() != null) {
            b.append(";SameSite=");
            String sameSite = cookie.getSameSite().name();
            b.append(sameSite.charAt(0)).append(sameSite.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    private static @Nullable Date parseExpires(String value) {
        // RFC 1123 (e.g. Netty's), then the old Netscape format
        try {
            return Date.from(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            // try the old format
        }
        SimpleDateFormat format = new SimpleDateFormat(OLD_COOKIE_PATTERN, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            return format.parse(value);
        } catch (ParseException e) {
            return null;
        }
    }

    private static NewCookie.@Nullable SameSite sameSite(String value) {
        for (NewCookie.SameSite sameSite : NewCookie.SameSite.values()) {
            if (sameSite.name().equalsIgnoreCase(value)) {
                return sameSite;
            }
        }
        return null;
    }

    private void quote(StringBuilder b, String value) {

        if (MediaTypeHeaderDelegate.quoted(value)) {
            // a quote or a backslash in a quoted string is a quoted-pair
            b.append('"');
            b.append(value.replace("\\", "\\\\").replace("\"", "\\\""));
            b.append('"');
        } else {
            b.append(value);
        }
    }
}
