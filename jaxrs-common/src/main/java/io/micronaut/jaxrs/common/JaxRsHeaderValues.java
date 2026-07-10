/*
 * Copyright 2017-2026 original authors
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

/**
 * Internal header serialization validation.
 */
@Internal
public final class JaxRsHeaderValues {

    private static final String TOKEN_SEPARATORS = "()<>@,;:\\\"/[]?={} \t";

    private JaxRsHeaderValues() {
    }

    /**
     * Rejects values that can break a single HTTP header field.
     *
     * @param value The header value
     * @return The validated value
     */
    public static String validateHeaderValue(String value) {
        JaxRsUtils.requireNonNull("value", value);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || (c < 0x20 && c != '\t') || c == 0x7f) {
                throw new IllegalArgumentException("Invalid HTTP header value");
            }
        }
        return value;
    }

    /**
     * Rejects invalid HTTP field names and header parameter names.
     *
     * @param token The token
     * @return The validated token
     */
    public static String validateToken(String token) {
        JaxRsUtils.requireNonNull("token", token);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("HTTP header token cannot be empty");
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= 0x20 || c >= 0x7f || TOKEN_SEPARATORS.indexOf(c) >= 0) {
                throw new IllegalArgumentException("Invalid HTTP header token: " + token);
            }
        }
        return token;
    }

    /**
     * Quotes and escapes a header parameter value after validating it cannot inject
     * additional header fields.
     *
     * @param value The value
     * @return The quoted value
     */
    public static String quoteParameterValue(String value) {
        validateHeaderValue(value);
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                result.append('\\');
            }
            result.append(c);
        }
        result.append('"');
        return result.toString();
    }
}
