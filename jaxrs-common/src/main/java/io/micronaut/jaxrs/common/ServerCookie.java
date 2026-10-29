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

import org.jspecify.annotations.Nullable;

/**
 * The quoting of a cookie value.  Originally forked from Tomcat.
 */
final class ServerCookie {
    private static final String TSPECIALS = ",; ";

    private ServerCookie() {
    }

    /**
     * A value of a cookie, quoted when it is not a token.
     *
     * @param value The value
     * @return The value, quoted if needed
     */
    static String quoted(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        } else if (containsCTL(value)) {
            throw new IllegalArgumentException("Invalid control character in cookie value: " + value);
        } else if (alreadyQuoted(value)) {
            return '"' + escapeDoubleQuotes(value, 1, value.length() - 1) + '"';
        } else if (!isToken(value)) {
            return '"' + escapeDoubleQuotes(value, 0, value.length()) + '"';
        }
        return value;
    }

    /*
     * Tests a string and returns true if the string counts as a
     * reserved token in the Java language.
     *
     * @param value the <code>String</code> to be tested
     *
     * @return      <code>true</code> if the <code>String</code> is a reserved
     *              token; <code>false</code> if it is not
     */
    private static boolean isToken(String value) {
        int len = value.length();
        for (int i = 0; i < len; i++) {
            if (TSPECIALS.indexOf(value.charAt(i)) != -1) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsCTL(String value) {
        int len = value.length();
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c >= 0x7f) {
                if (c == 0x09) {
                    continue; //allow horizontal tabs
                }
                return true;
            }
        }
        return false;
    }

    private static boolean alreadyQuoted(String value) {
        return value.charAt(0) == '\"' && value.charAt(value.length() - 1) == '\"';
    }

    /**
     * Escapes any double quotes in the given string.
     *
     * @param s          the input string
     * @param beginIndex start index inclusive
     * @param endIndex   exclusive
     * @return The (possibly) escaped string
     */
    private static String escapeDoubleQuotes(String s, int beginIndex, int endIndex) {
        if (s.indexOf('"') == -1) {
            return s;
        }

        StringBuilder b = new StringBuilder();
        for (int i = beginIndex; i < endIndex; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                b.append(c);
                //ignore the character after an escape, just append it
                if (++i >= endIndex) { //NOSONAR
                    throw new IllegalArgumentException("Invalid character in cookie: " + s);
                }
                b.append(s.charAt(i));
            } else if (c == '"') {
                b.append('\\').append('"');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

}
