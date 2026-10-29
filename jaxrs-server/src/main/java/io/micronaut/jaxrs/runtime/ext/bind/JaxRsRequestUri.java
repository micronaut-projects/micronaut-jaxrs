/*
 * Copyright 2017-2022 original authors
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
package io.micronaut.jaxrs.runtime.ext.bind;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parsed parts of the URI of a request, parsed once per request when first used, not once per
 * parameter that reads them: kept in an attribute of the request, for its current URI, as a
 * pre-matching filter can replace it.
 *
 * <p>The maps and lists are not copied: read-only to their users.</p>
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public final class JaxRsRequestUri {

    private static final String ATTRIBUTE = JaxRsRequestUri.class.getName();

    private final URI uri;
    private @Nullable Map<String, List<String>> rawQuery;
    private @Nullable Map<String, List<String>> decodedQuery;
    private @Nullable Map<String, List<String>> boundQuery;
    private String @Nullable [] rawPathSegments;

    private JaxRsRequestUri(URI uri) {
        this.uri = uri;
    }

    /**
     * @param request The request
     * @return The parsed URI of the request, for its current URI
     */
    public static JaxRsRequestUri of(HttpRequest<?> request) {
        URI uri = request.getUri();
        JaxRsRequestUri parsed = request.getAttribute(ATTRIBUTE, JaxRsRequestUri.class).orElse(null);
        if (parsed == null || parsed.uri != uri) {
            parsed = new JaxRsRequestUri(uri);
            request.setAttribute(ATTRIBUTE, parsed);
        }
        return parsed;
    }

    /**
     * @param decode Whether the names and values are decoded
     * @return The query parameters, see {@link UriInfoImpl#queryParameters(URI, boolean)}
     */
    public synchronized Map<String, List<String>> queryParameters(boolean decode) {
        Map<String, List<String>> query = decode ? decodedQuery : rawQuery;
        if (query == null) {
            query = UriInfoImpl.queryParameters(uri, decode);
            if (decode) {
                decodedQuery = query;
            } else {
                rawQuery = query;
            }
        }
        return query;
    }

    /**
     * The raw values of the query parameters by decoded name, for the parameters of resource
     * methods: the values of the names that decode to the same name are in the order of the
     * first occurrence of each raw name.
     *
     * @param name The decoded name
     * @return The raw values, empty if none
     */
    public synchronized List<String> rawQueryValues(String name) {
        Map<String, List<String>> bound = boundQuery;
        if (bound == null) {
            bound = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : queryParameters(false).entrySet()) {
                bound.computeIfAbsent(UriInfoImpl.decodeQuery(entry.getKey()), k -> new ArrayList<>(1)).addAll(entry.getValue());
            }
            boundQuery = bound;
        }
        return bound.getOrDefault(name, List.of());
    }

    /**
     * @return The segments of the raw path, split by slashes, with the empty ones
     */
    public synchronized String[] rawPathSegments() {
        String[] segments = rawPathSegments;
        if (segments == null) {
            segments = uri.getRawPath().split("/", -1);
            rawPathSegments = segments;
        }
        return segments;
    }
}
