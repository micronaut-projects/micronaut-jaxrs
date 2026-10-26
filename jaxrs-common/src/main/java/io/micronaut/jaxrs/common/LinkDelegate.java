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
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriBuilderException;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Internal
final class LinkDelegate implements RuntimeDelegate.HeaderDelegate<Link> {

    @Override
    public Link fromString(String value) throws IllegalArgumentException {
        ArgumentUtils.requireNonNull("value", value);
        Parser parser = new Parser(value, 0);
        parser.parse();
        if (parser.curr < value.length()) {
            throw new IllegalArgumentException("Unable to parse Link header. Too many links in declaration: " + value);
        }
        return parser.getLink();
    }

    /**
     * The links of a header value, which has one link or several separated by commas
     * (RFC 8288, 3).
     *
     * @param value The value of a Link header
     * @return The links
     */
    static List<Link> parseAll(String value) {
        ArgumentUtils.requireNonNull("value", value);
        List<Link> links = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            Parser parser = new Parser(value, start);
            if (parser.skipSeparators()) {
                parser.parse();
                links.add(parser.getLink());
            }
            start = parser.curr;
        }
        return links;
    }

    @Override
    public String toString(Link value) throws IllegalArgumentException {
        ArgumentUtils.requireNonNull("value", value);
        StringBuilder buf = new StringBuilder("<");
        buf.append(value.getUri().toString()).append(">");

        for (Map.Entry<String, String> entry : value.getParams().entrySet()) {
            buf.append("; ").append(entry.getKey()).append("=\"");
            // a quoted-string, its quotes and backslashes escaped (RFC 9110 5.6.4)
            String param = entry.getValue();
            for (int i = 0; i < param.length(); i++) {
                char c = param.charAt(i);
                if (c == '"' || c == '\\') {
                    buf.append('\\');
                }
                buf.append(c);
            }
            buf.append('"');
        }

        return buf.toString();
    }

    /**
     * Parser impl.
     */
    private static final class Parser {

        public static final String PARSE_ERROR_MESSAGE = "Unable to parse Link header.  No end to parameter: ";
        private final String value;
        private final Link.Builder builder;
        private int curr;

        Parser(final String value, int start) {
            this.value = value;
            this.curr = start;
            builder = new LinkBuilderImpl();
        }

        /**
         * Skip the separators before a link.
         *
         * @return Whether there is a link
         */
        boolean skipSeparators() {
            while (curr < value.length() && (value.charAt(curr) == ',' || Character.isWhitespace(value.charAt(curr)))) {
                curr++;
            }
            return curr < value.length();
        }

        public Link getLink() {
            try {
                return builder.build();
            } catch (UriBuilderException e) {
                throw new IllegalArgumentException("Unable to parse Link header. Invalid link: " + value, e);
            }
        }

        void parse() {
            String href = null;
            MultivaluedMap<String, String> attributes = new MultivaluedHashMap<>();
            while (curr < value.length()) {

                char c = value.charAt(curr);
                if (c == '<') {
                    if (href != null) {
                        throw new IllegalArgumentException("Unable to parse Link header. Too many links in declaration: " + value);
                    }
                    href = parseLink();
                } else if (c == ';' || c == ' ') {
                    curr++;
                } else if (c == ',') {
                    // the end of this link, another one follows
                    curr++;
                    break;
                } else {
                    parseAttribute(attributes);
                }
            }
            populateLink(href, attributes);


        }

        void populateLink(@Nullable String href, MultivaluedMap<String, String> attributes) {
            builder.uri(href);
            for (Entry<String, List<String>> entry : attributes.entrySet()) {
                List<String> values = entry.getValue();
                // RFC 8288 3.3 and 3.4.1: the occurrences of rel, title and type after the first are ignored
                switch (entry.getKey()) {
                    case "rel" -> builder.rel(values.get(0));
                    case "title" -> builder.title(values.get(0));
                    case "type" -> builder.type(values.get(0));
                    default -> {
                        for (String val : values) {
                            builder.param(entry.getKey(), val);
                        }
                    }
                }
            }
        }

        String parseLink() {
            int end = value.indexOf('>', curr);
            if (end == -1) {
                throw new IllegalArgumentException("Unable to parse Link header.  No end to link:" + value);
            }
            String href = value.substring(curr + 1, end);
            curr = end + 1;
            return href;
        }

        @SuppressWarnings("java:S3776")
        void parseAttribute(MultivaluedMap<String, String> attributes) {
            int end = value.indexOf('=', curr);
            if (end == -1 || end + 1 >= value.length()) {
                throw new IllegalArgumentException(PARSE_ERROR_MESSAGE + value);
            }
            String name = value.substring(curr, end);
            name = name.trim();
            curr = end + 1;
            String val;
            if (curr >= value.length()) {
                val = "";
            } else {

                if (value.charAt(curr) == '"') {
                    if (curr + 1 >= value.length()) {
                        throw new IllegalArgumentException(PARSE_ERROR_MESSAGE + value);
                    }
                    curr++;
                    // a quoted-string, whose quoted-pairs are unescaped (RFC 9110 5.6.4)
                    StringBuilder buf = new StringBuilder();
                    while (curr < value.length() && value.charAt(curr) != '"') {
                        char c = value.charAt(curr);
                        if (c == '\\' && curr + 1 < value.length()) {
                            c = value.charAt(++curr);
                        }
                        buf.append(c);
                        curr++;
                    }
                    if (curr >= value.length()) {
                        throw new IllegalArgumentException(PARSE_ERROR_MESSAGE + value);
                    }
                    val = buf.toString();
                    curr++;
                } else {
                    StringBuilder buf = new StringBuilder();
                    while (curr < value.length()) {
                        char c = value.charAt(curr);
                        if (c == ',' || c == ';') {
                            break;
                        }
                        buf.append(value.charAt(curr));
                        curr++;
                    }
                    val = buf.toString();
                }
            }
            attributes.add(name, val);

        }

    }
}
