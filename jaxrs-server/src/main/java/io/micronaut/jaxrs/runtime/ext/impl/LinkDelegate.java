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

import javax.ws.rs.core.Link;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.RuntimeDelegate;

import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Internal
final class LinkDelegate implements RuntimeDelegate.HeaderDelegate<Link> {

    @Override
    public Link fromString(String value) throws IllegalArgumentException {
        ArgumentUtils.requireNonNull("value", value);
        Parser parser = new Parser(value);
        parser.parse();
        return parser.getLink();
    }

    @Override
    public String toString(Link value) throws IllegalArgumentException {
        ArgumentUtils.requireNonNull("value", value);
        StringBuilder buf = new StringBuilder("<");
        buf.append(value.getUri().toString()).append(">");

        for (Map.Entry<String, String> entry : value.getParams().entrySet()) {
            buf.append("; ").append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"");
        }

        return buf.toString();
    }

    /**
     * Parser impl.
     */
    private static final class Parser {
        private int curr;
        private String value;
        private Link.Builder builder;

        Parser(final String value) {
            this.value = value;
            builder = new LinkBuilderImpl();
        }

        public Link getLink() {
            return builder.build();
        }

        void parse() {
            String href = null;
            MultivaluedMap<String, String> attributes = new MultivaluedHashMap<>();
            while (curr < value.length()) {

                char c = value.charAt(curr);
                if (c == '<') {
                    if (href != null) {
                        throw new IllegalArgumentException("Unable to parse Link header. Too many links in declaration: "  + value);
                    }
                    href = parseLink();
                } else if (c == ';' || c == ' ') {
                    curr++;
                } else {
                    parseAttribute(attributes);
                }
            }
            populateLink(href, attributes);


        }

        void populateLink(String href, MultivaluedMap<String, String> attributes) {
            builder.uri(href);
            for (String name : attributes.keySet()) {
                List<String> values = attributes.get(name);
                switch (name) {
                    case "rel":
                        for (String val : values) {
                            builder.rel(val);
                        }
                        break;
                    case "title":
                        for (String val : values) {
                            builder.title(val);
                        }

                        break;
                    case "type":
                        for (String val : values) {
                            builder.type(val);
                        }

                        break;
                    default:
                        for (String val : values) {
                            builder.param(name, val);
                        }

                        break;
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

        void parseAttribute(MultivaluedMap<String, String> attributes) {
            int end = value.indexOf('=', curr);
            if (end == -1 || end + 1 >= value.length()) {
                throw new IllegalArgumentException("Unable to parse Link header.  No end to parameter: " + value);
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
                        throw new IllegalArgumentException("Unable to parse Link header.  No end to parameter: " + value);
                    }
                    curr++;
                    end = value.indexOf('"', curr);
                    if (end == -1) {
                        throw new IllegalArgumentException("Unable to parse Link header.  No end to parameter: " + value);
                    }
                    val = value.substring(curr, end);
                    curr = end + 1;
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
