package io.micronaut.jaxrs.runtime.ext.impl;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.jaxrs.runtime.core.ParameterParser;

import javax.ws.rs.core.NewCookie;
import javax.ws.rs.ext.RuntimeDelegate;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Forked from RESTEasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Internal
class NewCookieHeaderDelegate implements RuntimeDelegate.HeaderDelegate {
    private static final String OLD_COOKIE_PATTERN = "EEE, dd-MMM-yyyy HH:mm:ss z";

    @Override
    public Object fromString(String newCookie) throws IllegalArgumentException {
        ArgumentUtils.requireNonNull("newCookie", newCookie);
        String cookieName = null;
        String cookieValue = null;
        String comment = null;
        String domain = null;
        int maxAge = NewCookie.DEFAULT_MAX_AGE;
        String path = null;
        boolean secure = false;
        int version = NewCookie.DEFAULT_VERSION;
        boolean httpOnly = false;
        Date expiry = null;

        ParameterParser parser = new ParameterParser();
        Map<String, String> map = parser.parse(newCookie, ';');

        for (Map.Entry<String, String> entry : map.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name.equalsIgnoreCase("Comment")) {
                comment = value;
            } else if (name.equalsIgnoreCase("Domain")) {
                domain = value;
            } else if (name.equalsIgnoreCase("Max-Age")) {
                maxAge = Integer.parseInt(value);
            } else if (name.equalsIgnoreCase("Path")) {
                path = value;
            } else if (name.equalsIgnoreCase("Secure")) {
                secure = true;
            } else if (name.equalsIgnoreCase("Version")) {
                version = Integer.parseInt(value);
            } else if (name.equalsIgnoreCase("HttpOnly")) {
                httpOnly = true;
            } else if (name.equalsIgnoreCase("Expires")) {
                try {
                    expiry = new SimpleDateFormat(OLD_COOKIE_PATTERN, Locale.US).parse(value);
                } catch (ParseException e) {
                    // ignore
                }
            } else {
                cookieName = name;
                cookieValue = value;
            }

        }

        if (cookieValue == null) {
            cookieValue = "";
        }

        return new NewCookie(cookieName, cookieValue, path, domain, version, comment, maxAge, expiry, secure, httpOnly);

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
            b.append(new SimpleDateFormat(OLD_COOKIE_PATTERN).format(cookie.getExpiry()));
        }
        if (cookie.isSecure()) {
            b.append(";Secure");
        }
        if (cookie.isHttpOnly()) {
            b.append(";HttpOnly");
        }
        return b.toString();
    }

    private void quote(StringBuilder b, String value) {

        if (MediaTypeHeaderDelegate.quoted(value)) {
            b.append('"');
            b.append(value);
            b.append('"');
        } else {
            b.append(value);
        }
    }
}
