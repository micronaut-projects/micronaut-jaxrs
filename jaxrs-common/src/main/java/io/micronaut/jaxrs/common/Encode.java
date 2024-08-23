/*
 * Copyright 2017-2024 original authors
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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The encoder utility class.
 * Originally forked from Resteasy.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @author Denis Stepanov
 * @since 4.7
 */
@Internal
final class Encode {

    /**
     * A regex pattern that searches for a URI template parameter in the form of {*}.
     */
    public static final Pattern URI_TEMPLATE_PATTERN = Pattern.compile("(\\{([^}]+)})");

    private static final Pattern NON_CODES = Pattern.compile("%([^a-fA-F0-9]|[a-fA-F0-9]$|$|[a-fA-F0-9][^a-fA-F0-9])");

    private static final String UTF_8 = StandardCharsets.UTF_8.name();

    private static final Pattern PARAM_REPLACEMENT = Pattern.compile("_resteasy_uri_parameter");

    private static final String[] PATH_ENCODING = new String[128];
    private static final String[] PATH_SEGMENT_ENCODING = new String[128];
    private static final String[] MATRIX_PARAMETER_ENCODING = new String[128];
    private static final String[] QUERY_NAME_VALUE_ENCODING = new String[128];
    private static final String[] QUERY_STRING_ENCODING = new String[128];

    private static final char OPEN_CURLY_REPLACEMENT = 6;
    private static final char CLOSE_CURLY_REPLACEMENT = 7;

    static {
        /*
         * Encode via <a href="https://www.ietf.org/rfc/rfc3986.txt">RFC 3986</a>. PCHAR is allowed allong with '/'
         *
         * unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
         * sub-delims = "!" / "$" / "&" / "'" / "(" / ")"
         * / "*" / "+" / "," / ";" / "="
         * pchar = unreserved / pct-encoded / sub-delims / ":" / "@"
         *
         */
        for (int i = 0; i < 128; i++) {
            if (i >= 'a' && i <= 'z') {
                continue;
            }
            if (i >= 'A' && i <= 'Z') {
                continue;
            }
            if (i >= '0' && i <= '9') {
                continue;
            }
            switch ((char) i) {
                case '-':
                case '.':
                case '_':
                case '~':
                case '!':
                case '$':
                case '&':
                case '\'':
                case '(':
                case ')':
                case '*':
                case '+':
                case ',':
                case '/':
                case ';':
                case '=':
                case ':':
                case '@':
                    continue;
                default:
                    PATH_ENCODING[i] = encodeString(String.valueOf((char) i));
            }
        }
        PATH_ENCODING[' '] = "%20";
        System.arraycopy(PATH_ENCODING, 0, MATRIX_PARAMETER_ENCODING, 0, PATH_ENCODING.length);
        MATRIX_PARAMETER_ENCODING[';'] = "%3B";
        MATRIX_PARAMETER_ENCODING['='] = "%3D";
        MATRIX_PARAMETER_ENCODING['/'] = "%2F"; // RESTEASY-729
        System.arraycopy(PATH_ENCODING, 0, PATH_SEGMENT_ENCODING, 0, PATH_ENCODING.length);
        PATH_SEGMENT_ENCODING['/'] = "%2F";
        /*
         * Encode via <a href="https://www.ietf.org/rfc/rfc3986.txt">RFC 3986</a>.
         *
         * unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
         * space encoded as '+'
         *
         */
        for (int i = 0; i < 128; i++) {
            if (i >= 'a' && i <= 'z') {
                continue;
            }
            if (i >= 'A' && i <= 'Z') {
                continue;
            }
            if (i >= '0' && i <= '9') {
                continue;
            }
            switch ((char) i) {
                case '-':
                case '.':
                case '_':
                case '~':
                    continue;
                case '?':
                    QUERY_NAME_VALUE_ENCODING[i] = "%3F";
                    continue;
                case ' ':
                    QUERY_NAME_VALUE_ENCODING[i] = "+";
                    continue;
                default:
                    QUERY_NAME_VALUE_ENCODING[i] = encodeString(String.valueOf((char) i));
            }
        }

        /*
         * query = *( pchar / "/" / "?" )
         *
         */
        for (int i = 0; i < 128; i++) {
            if (i >= 'a' && i <= 'z') {
                continue;
            }
            if (i >= 'A' && i <= 'Z') {
                continue;
            }
            if (i >= '0' && i <= '9') {
                continue;
            }
            switch ((char) i) {
                case '-':
                case '.':
                case '_':
                case '~':
                case '!':
                case '$':
                case '&':
                case '\'':
                case '(':
                case ')':
                case '*':
                case '+':
                case ',':
                case ';':
                case '=':
                case ':':
                case '@':
                case '?':
                case '/':
                    continue;
                case ' ':
                    QUERY_STRING_ENCODING[i] = "%20";
                    continue;
                default:
                    QUERY_STRING_ENCODING[i] = encodeString(String.valueOf((char) i));
            }
        }
    }

    /**
     * Keep encoded values "%..." and template parameters intact.
     *
     * @param value query string
     * @return encoded query string
     */
    public static String encodeQueryString(String value) {
        return encodeValue(value, QUERY_STRING_ENCODING);
    }

    /**
     * Keep encoded values "%...", matrix parameters, template parameters, and '/' characters intact.
     *
     * @param value path
     * @return encoded path
     */
    public static String encodePath(String value) {
        return encodeValue(value, PATH_ENCODING);
    }

    /**
     * Keep encoded values "%...", matrix parameters and template parameters intact.
     *
     * @param value path segment
     * @return encoded path segment
     */
    public static String encodePathSegment(String value) {
        return encodeValue(value, PATH_SEGMENT_ENCODING);
    }

    /**
     * Keep encoded values "%..." and template parameters intact.
     *
     * @param value uri fragment
     * @return encoded uri fragment
     */
    public static String encodeFragment(String value) {
        return encodeValue(value, QUERY_STRING_ENCODING);
    }

    /**
     * Keep encoded values "%..." and template parameters intact.
     *
     * @param value matrix parameter
     * @return encoded matrix parameter
     */
    public static String encodeMatrixParam(String value) {
        return encodeValue(value, MATRIX_PARAMETER_ENCODING);
    }

    /**
     * Keep encoded values "%..." and template parameters intact.
     *
     * @param value query parameter
     * @return encoded query parameter
     */
    public static String encodeQueryParam(String value) {
        return encodeValue(value, QUERY_NAME_VALUE_ENCODING);
    }

    /**
     * Encode '%' if it is not an encoding sequence.
     *
     * @param string value to encode
     * @return encoded value
     */
    public static String encodeNonCodes(String string) {
        Matcher matcher = NON_CODES.matcher(string);
        StringBuilder builder = new StringBuilder();

        // FYI: we do not use the no-arg matcher.find()
        //      coupled with matcher.appendReplacement()
        //      because the matched text may contain
        //      a second % and we must make sure we
        //      encode it (if necessary).
        int idx = 0;
        while (matcher.find(idx)) {
            int start = matcher.start();
            builder.append(string, idx, start);
            builder.append("%25");
            idx = start + 1;
        }
        builder.append(string.substring(idx));
        return builder.toString();
    }

    public static boolean savePathParams(String segmentString, StringBuilder newSegment, List<String> params) {
        boolean foundParam = false;
        // Regular expressions can have '{' and '}' characters.  Replace them to do match
        CharSequence segment = replaceEnclosedCurlyBracesCS(segmentString);
        Matcher matcher = URI_TEMPLATE_PATTERN.matcher(segment);
        int start = 0;
        while (matcher.find()) {
            newSegment.append(segment, start, matcher.start());
            foundParam = true;
            String group = matcher.group();
            // Regular expressions can have '{' and '}' characters.  Recover earlier replacement
            params.add(recoverEnclosedCurlyBraces(group));
            newSegment.append("_resteasy_uri_parameter");
            start = matcher.end();
        }
        newSegment.append(segment, start, segment.length());
        return foundParam;
    }

    /**
     * Keep encoded values "%..." and template parameters intact i.e. "{x}".
     *
     * @param segment  value to encode
     * @param encoding encoding
     * @return encoded value
     */
    public static String encodeValue(String segment, String[] encoding) {
        ArrayList<String> params = new ArrayList<String>();
        boolean foundParam = false;
        StringBuilder newSegment = new StringBuilder();
        if (savePathParams(segment, newSegment, params)) {
            foundParam = true;
            segment = newSegment.toString();
        }
        String result = encodeFromArray(segment, encoding, false);
        result = encodeNonCodes(result);
        segment = result;
        if (foundParam) {
            segment = pathParamReplacement(segment, params);
        }
        return segment;
    }

    /**
     * Encode via <a href="https://www.ietf.org/rfc/rfc3986.txt">RFC 3986</a>. PCHAR is allowed allong with '/'.
     * <p>
     * unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
     * sub-delims = "!" / "$" / "&#x26;" / "'" / "(" / ")"
     * / "*" / "+" / "," / ";" / "="
     * pchar = unreserved / pct-encoded / sub-delims / ":" / "@"
     *
     * @param segment value to encode
     * @return encoded value
     */
    public static String encodePathAsIs(String segment) {
        return encodeFromArray(segment, PATH_ENCODING, true);
    }

    /**
     * Keep any valid encodings from string i.e. keep "%2D" but don't keep "%p".
     *
     * @param segment value to encode
     * @return encoded value
     */
    public static String encodePathSaveEncodings(String segment) {
        String result = encodeFromArray(segment, PATH_ENCODING, false);
        result = encodeNonCodes(result);
        return result;
    }

    /**
     * Encode via <a href="https://www.ietf.org/rfc/rfc3986.txt">RFC 3986</a>. PCHAR is allowed allong with '/'.
     * <p>
     * unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
     * sub-delims = "!" / "$" / "&#x26;" / "'" / "(" / ")"
     * / "*" / "+" / "," / ";" / "="
     * pchar = unreserved / pct-encoded / sub-delims / ":" / "@"
     *
     * @param segment value to encode
     * @return encoded value
     */
    public static String encodePathSegmentAsIs(String segment) {
        return encodeFromArray(segment, PATH_SEGMENT_ENCODING, true);
    }

    /**
     * Keep any valid encodings from string i.e. keep "%2D" but don't keep "%p"
     *
     * @param segment value to encode
     * @return encoded value
     */
    public static String encodePathSegmentSaveEncodings(String segment) {
        String result = encodeFromArray(segment, PATH_SEGMENT_ENCODING, false);
        result = encodeNonCodes(result);
        return result;
    }

    /**
     * Encodes everything of a query parameter name or value.
     *
     * @param nameOrValue value to encode
     * @return encoded value
     */
    public static String encodeQueryParamAsIs(String nameOrValue) {
        return encodeFromArray(nameOrValue, QUERY_NAME_VALUE_ENCODING, true);
    }

    /**
     * Keep any valid encodings from string i.e. keep "%2D" but don't keep "%p".
     *
     * @param segment value to encode
     * @return encoded value
     */
    public static String encodeQueryParamSaveEncodings(String segment) {
        String result = encodeFromArray(segment, QUERY_NAME_VALUE_ENCODING, false);
        result = encodeNonCodes(result);
        return result;
    }

    private static String encodeFromArray(String segment, String[] encodingMap, boolean encodePercent) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char currentChar = segment.charAt(i);
            if (!encodePercent && currentChar == '%') {
                result.append(currentChar);
                continue;
            }
            if (Character.isHighSurrogate(currentChar)) {
                String part = segment.substring(i, i + 2);
                result.append(URLEncoder.encode(part, StandardCharsets.UTF_8));
                ++i;
                continue;
            }
            String encoding = encode(currentChar, encodingMap);
            if (encoding == null) {
                result.append(currentChar);
            } else {
                result.append(encoding);
            }
        }
        return result.toString();
    }

    /**
     * @param zhar        integer representation of character
     * @param encodingMap encoding map
     * @return URL encoded character
     */
    private static String encode(int zhar, String[] encodingMap) {
        String encoded;
        if (zhar < encodingMap.length) {
            encoded = encodingMap[zhar];
        } else {
            encoded = encodeString(Character.toString((char) zhar));
        }
        return encoded;
    }

    /**
     * Calls URLEncoder.encode(s, "UTF-8") on given input.
     *
     * @param s string to encode
     * @return encoded string returned by URLEncoder.encode(s, "UTF-8")
     */
    public static String encodeString(String s) {
        try {
            return URLEncoder.encode(s, UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static String pathParamReplacement(String segment, List<String> params) {
        StringBuilder newSegment = new StringBuilder();
        Matcher matcher = PARAM_REPLACEMENT.matcher(segment);
        int i = 0;
        int start = 0;
        while (matcher.find()) {
            newSegment.append(segment, start, matcher.start());
            String replacement = params.get(i++);
            newSegment.append(replacement);
            start = matcher.end();
        }
        newSegment.append(segment, start, segment.length());
        segment = newSegment.toString();
        return segment;
    }

    /**
     * A cheaper (memory-wise) version of replaceEnclosedCurlyBraces(String str).
     *
     * @param str input string
     * @return replaced output
     */
    public static CharSequence replaceEnclosedCurlyBracesCS(String str) {
        int open = 0;
        CharSequence cs = str;
        char[] chars = null;
        for (int i = 0; i < str.length(); i++) {
            if (cs.charAt(i) == '{') {
                if (open != 0) {
                    if (cs == str) {
                        chars = str.toCharArray();
                        cs = new ArrayCharSequence(chars);
                    }
                    chars[i] = OPEN_CURLY_REPLACEMENT;
                }
                open++;
            } else if (cs.charAt(i) == '}') {
                open--;
                if (open != 0) {
                    if (cs == str) {
                        chars = str.toCharArray();
                        cs = new ArrayCharSequence(chars);
                    }
                    chars[i] = CLOSE_CURLY_REPLACEMENT;
                }
            }
        }
        return cs;
    }

    public static String recoverEnclosedCurlyBraces(String str) {
        return str.replace(OPEN_CURLY_REPLACEMENT, '{').replace(CLOSE_CURLY_REPLACEMENT, '}');
    }

    /**
     * A CharSequence backed by a char[] (no copy on creation).
     */
    private static final class ArrayCharSequence implements CharSequence {
        private final char[] buf;
        private final int offset;
        private final int count;

        public ArrayCharSequence(final char[] buff) {
            this(buff, 0, buff.length);
        }

        public ArrayCharSequence(final char[] buff, final int offset, final int count) {
            this.buf = buff;
            this.offset = offset;
            this.count = count;
        }

        public char charAt(int index) {
            if (index < 0 || index >= count) {
                throw new StringIndexOutOfBoundsException(index);
            }
            return buf[offset + index];
        }

        public int length() {
            return count;
        }

        public CharSequence subSequence(int beginIndex, int endIndex) {
            if (beginIndex < 0) {
                throw new StringIndexOutOfBoundsException(beginIndex);
            }
            if (endIndex > count) {
                throw new StringIndexOutOfBoundsException(endIndex);
            }
            if (beginIndex > endIndex) {
                throw new StringIndexOutOfBoundsException(endIndex - beginIndex);
            }
            return ((beginIndex == 0) && (endIndex == count))
                ? this
                : new ArrayCharSequence(buf, offset + beginIndex, endIndex - beginIndex);
        }

        public String toString() {
            return new String(this.buf, this.offset, this.count);
        }
    }
}
