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

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The URI templates of the {@link JaxRsUriBuilder}: the expansion of their parameters, and their
 * protection from the encoding and the edition of the text around them. A parameter is
 * {@code {name}} or {@code {name: regex}}, whose regular expression may have braces.
 * Originally forked from Resteasy.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
final class JaxRsUriTemplate {

    private static final String PARAM_NAME_REGEX = "\\w[\\w.-]*";
    private static final String PARAM_REGEX_REGEX = "[^{}][^{}]*";
    private static final Pattern PARAM_PATTERN = Pattern.compile("\\{\\s*(" + PARAM_NAME_REGEX + ")\\s*(:\\s*("
        + PARAM_REGEX_REGEX + "))?}");
    // a template parameter in the form of {*}
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("(\\{([^}]+)})");

    private static final String MARKER = "_jaxrs_uri_parameter";
    private static final Pattern MARKER_PATTERN = Pattern.compile(MARKER);

    private static final char OPEN_CURLY_REPLACEMENT = 6;
    private static final char CLOSE_CURLY_REPLACEMENT = 7;

    private JaxRsUriTemplate() {
    }

    /**
     * Append a template with its parameters replaced with their values.
     *
     * @param template       The template
     * @param values         The values by parameter name, asked in the order of the parameters
     * @param keepUnresolved Whether a parameter without a value is kept, else it fails
     * @param out            The output
     * @param encoder        The encoding of a value
     * @param nullMessage    The message of the failure of a null value, before the parameter name
     * @return The output
     */
    static StringBuilder expand(String template, Map<String, ?> values, boolean keepUnresolved, StringBuilder out,
                                UnaryOperator<String> encoder, String nullMessage) {
        if (template.indexOf('{') == -1) {
            return out.append(template);
        }
        Matcher matcher = PARAM_PATTERN.matcher(replaceEnclosedCurlyBraces(template));
        int start = 0;
        while (matcher.find()) {
            out.append(template, start, matcher.start());
            String param = matcher.group(1);
            if (!values.containsKey(param)) {
                if (keepUnresolved) {
                    out.append(matcher.group());
                    start = matcher.end();
                    continue;
                }
                throw new IllegalArgumentException("Path parameter not provided " + param);
            }
            Object value = values.get(param);
            if (value == null) {
                throw new IllegalArgumentException(nullMessage + param);
            }
            out.append(encoder.apply(value.toString()));
            start = matcher.end();
        }
        return out.append(template, start, template.length());
    }

    /**
     * Replace the template parameters of a value with markers, so that its other characters can be
     * changed without changing them, e.g. encoded.
     *
     * @param value The value
     * @return The value with markers, and its template parameters: the nested braces of a value
     * without template parameters are replaced as well
     */
    static Protected protect(String value) {
        List<String> templates = new ArrayList<>();
        // the regular expressions of the parameters can have '{' and '}' characters
        CharSequence replaced = replaceEnclosedCurlyBraces(value);
        Matcher matcher = TEMPLATE_PATTERN.matcher(replaced);
        StringBuilder text = new StringBuilder();
        int start = 0;
        while (matcher.find()) {
            text.append(replaced, start, matcher.start());
            templates.add(recoverEnclosedCurlyBraces(matcher.group()));
            text.append(MARKER);
            start = matcher.end();
        }
        text.append(replaced, start, replaced.length());
        return new Protected(text.toString(), templates);
    }

    /**
     * Replace the curly braces in a template parameter, e.g. the ones of its regular expression,
     * with other characters.
     *
     * @param str The value
     * @return The value, with the nested braces replaced
     */
    private static CharSequence replaceEnclosedCurlyBraces(String str) {
        int open = 0;
        CharSequence cs = str;
        char @Nullable [] chars = null;
        for (int i = 0; i < str.length(); i++) {
            if (cs.charAt(i) == '{') {
                if (open != 0) {
                    if (chars == null) {
                        chars = str.toCharArray();
                        cs = new ArrayCharSequence(chars);
                    }
                    chars[i] = OPEN_CURLY_REPLACEMENT;
                }
                open++;
            } else if (cs.charAt(i) == '}') {
                open--;
                if (open != 0) {
                    if (chars == null) {
                        chars = str.toCharArray();
                        cs = new ArrayCharSequence(chars);
                    }
                    chars[i] = CLOSE_CURLY_REPLACEMENT;
                }
            }
        }
        return cs;
    }

    private static String recoverEnclosedCurlyBraces(String str) {
        return str.replace(OPEN_CURLY_REPLACEMENT, '{').replace(CLOSE_CURLY_REPLACEMENT, '}');
    }

    /**
     * A value whose template parameters are replaced with markers.
     *
     * @param text      The value with markers
     * @param templates The template parameters, in order
     */
    record Protected(String text, List<String> templates) {

        /**
         * @return Whether the value has template parameters
         */
        boolean hasTemplates() {
            return !templates.isEmpty();
        }

        /**
         * Put the template parameters back in place of the markers.
         *
         * @param changed The value with markers, changed
         * @return The value with its template parameters
         */
        String restore(String changed) {
            if (templates.isEmpty()) {
                return changed;
            }
            StringBuilder restored = new StringBuilder();
            Matcher matcher = MARKER_PATTERN.matcher(changed);
            int i = 0;
            int start = 0;
            while (matcher.find()) {
                restored.append(changed, start, matcher.start());
                restored.append(templates.get(i++));
                start = matcher.end();
            }
            return restored.append(changed, start, changed.length()).toString();
        }
    }

    /**
     * A CharSequence backed by a char[] (no copy on creation).
     */
    private static final class ArrayCharSequence implements CharSequence {
        private final char[] buf;
        private final int offset;
        private final int count;

        ArrayCharSequence(final char[] buff) {
            this(buff, 0, buff.length);
        }

        ArrayCharSequence(final char[] buff, final int offset, final int count) {
            this.buf = buff;
            this.offset = offset;
            this.count = count;
        }

        @Override
        public char charAt(int index) {
            if (index < 0 || index >= count) {
                throw new StringIndexOutOfBoundsException(index);
            }
            return buf[offset + index];
        }

        @Override
        public int length() {
            return count;
        }

        @Override
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

        @Override
        public String toString() {
            return new String(this.buf, this.offset, this.count);
        }
    }
}
