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
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A simple parser intended to parse sequences of name/value pairs.
 * Parameter values are expected to be enclosed in quotes if they
 * contain unsafe characters, such as '=' characters or separators.
 * Parameter values are optional and can be omitted.
 * <p>
 * <code>param1 = value; param2 = "anything goes; really"; param3</code>
 * </p>
 *
 * <p>NOTICE: Forked from RESTEasy</p>
 *
 * @author <a href="mailto:oleg@ural.ru">Oleg Kalnichevski</a>
 */

@Internal
final class ParameterParser {
    /**
     * String to be parsed.
     */
    private char[] chars = new char[0];

    /**
     * Current position in the string.
     */
    private int pos = 0;

    /**
     * Maximum position in the string.
     */
    private int len = 0;

    /**
     * Start of a token.
     */
    private int i1 = 0;

    /**
     * End of a token.
     */
    private int i2 = 0;

    /**
     * Default ParameterParser constructor.
     */
    public ParameterParser() {
        super();
    }

    /**
     * Are there any characters left to parse?
     *
     * @return <code>true</code> if there are unparsed characters,
     * <code>false</code> otherwise.
     */
    private boolean hasChar() {
        return this.pos < this.len;
    }

    /**
     * A helper method to process the parsed token. This method removes
     * leading and trailing blanks as well as enclosing quotation marks,
     * when necessary.
     *
     * @param quoted <code>true</code> if quotation marks are expected,
     *               <code>false</code> otherwise.
     * @return the token
     */
    private @Nullable String getToken(boolean quoted) {
        // Trim leading white spaces
        while ((i1 < i2) && (Character.isWhitespace(chars[i1]))) {
            i1++;
        }
        // Trim trailing white spaces
        while ((i2 > i1) && (Character.isWhitespace(chars[i2 - 1]))) {
            i2--;
        }
        // Strip away quotation marks if necessary
        if (quoted
            && ((i2 - i1) >= 2)
            && (chars[i1] == '"')
            && (chars[i2 - 1] == '"')) {
            i1++;
            i2--;
            // RFC 9110 5.6.4: an empty quoted-string is the empty value, a quoted-pair is the escaped character
            StringBuilder result = new StringBuilder(i2 - i1);
            for (int i = i1; i < i2; i++) {
                if (chars[i] == '\\' && i + 1 < i2) {
                    i++;
                }
                result.append(chars[i]);
            }
            return result.toString();
        }
        String result = null;
        if (i2 > i1) {
            result = new String(chars, i1, i2 - i1);
        }
        return result;
    }

    /**
     * Tests if the given character is present in the array of characters.
     *
     * @param ch      the character to test for presence in the array of characters
     * @param charArray the array of characters to test against
     * @return <code>true</code> if the character is present in the array of
     * characters, <code>false</code> otherwise.
     */
    private boolean isOneOf(char ch, final char[] charArray) {
        boolean result = false;
        for (char c : charArray) {
            if (ch == c) {
                result = true;
                break;
            }
        }
        return result;
    }

    /**
     * Parses out a token until any of the given terminators
     * is encountered.
     *
     * @param terminators the array of terminating characters. Any of these
     *                    characters when encountered signify the end of the token
     * @return the token
     */
    private @Nullable String parseToken(final char[] terminators) {
        char ch;
        i1 = pos;
        i2 = pos;
        while (hasChar()) {
            ch = chars[pos];
            if (isOneOf(ch, terminators)) {
                break;
            }
            i2++;
            pos++;
        }
        return getToken(false);
    }

    /**
     * Parses out a token until any of the given terminators
     * is encountered outside the quotation marks.
     *
     * @param terminators the array of terminating characters. Any of these
     *                    characters when encountered outside the quotation marks signify the end
     *                    of the token
     * @return the token
     */
    private @Nullable String parseQuotedToken(final char[] terminators) {
        char ch;
        i1 = pos;
        i2 = pos;
        boolean quoted = false;
        boolean charEscaped = false;
        while (hasChar()) {
            ch = chars[pos];
            if (!quoted && isOneOf(ch, terminators)) {
                break;
            }
            if (!charEscaped && ch == '"') {
                quoted = !quoted;
            }
            charEscaped = (!charEscaped && ch == '\\');
            i2++;
            pos++;

        }
        return getToken(true);
    }

    /**
     * Extracts a map of name/value pairs from the given string. Names are
     * expected to be unique.
     *
     * @param str       the string that contains a sequence of name/value pairs
     * @param separator the name/value pairs separator
     * @return a map of name/value pairs
     */
    public Map<String, String> parse(final String str, char separator) {
        if (str == null) {
            return new HashMap<>();
        }
        char[] chars = str.toCharArray();
        return parse(chars, 0, chars.length, separator);
    }

    /**
     * Extracts a map of name/value pairs from the given array of
     * characters. Names are expected to be unique.
     *
     * @param chars     the array of characters that contains a sequence of
     *                  name/value pairs
     * @param offset    - the initial offset.
     * @param length    - the length.
     * @param separator the name/value pairs separator
     * @return a map of name/value pairs
     */
    private Map<String, String> parse(
        final char[] chars,
        int offset,
        int length,
        char separator) {

        HashMap<String, String> params = new HashMap<>();
        this.chars = chars;
        this.pos = offset;
        this.len = length;

        String paramName;
        String paramValue;
        while (hasChar()) {
            paramName = parseToken(new char[] {'=', separator});
            paramValue = null;
            if (hasChar() && (chars[pos] == '=')) {
                pos++; // skip '='
                paramValue = parseQuotedToken(new char[] {
                    separator});
            }
            if (hasChar() && (chars[pos] == separator)) {
                pos++; // skip separator
            }
            if ((paramName != null) && (!paramName.isEmpty())) {
                params.put(paramName, paramValue);
            }
        }
        return params;
    }
}
