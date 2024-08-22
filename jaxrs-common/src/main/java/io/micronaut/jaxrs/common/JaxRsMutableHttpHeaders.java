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
import io.micronaut.http.MutableHttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * Adapter class for JAR-RS headers.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public final class JaxRsMutableHttpHeaders extends JaxRsHttpHeaders {

    private final MutableHttpHeaders mutableHttpHeaders;

    /**
     * Default constructor.
     *
     * @param mutableHttpHeaders The Micronaut headers
     * @param isResponse         Is response headers
     */
    JaxRsMutableHttpHeaders(MutableHttpHeaders mutableHttpHeaders, boolean isResponse) {
        super(mutableHttpHeaders, isResponse);
        this.mutableHttpHeaders = mutableHttpHeaders;
    }

    /**
     * Create headers for a request.
     *
     * @param httpHeaders The headers
     * @return The headers
     */
    public static JaxRsMutableHttpHeaders forRequest(io.micronaut.http.MutableHttpHeaders httpHeaders) {
        return new JaxRsMutableHttpHeaders(httpHeaders, false);
    }

    /**
     * Create headers for a response.
     *
     * @param httpHeaders The headers
     * @return The headers
     */
    public static JaxRsMutableHttpHeaders forResponse(io.micronaut.http.MutableHttpHeaders httpHeaders) {
        return new JaxRsMutableHttpHeaders(httpHeaders, true);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
        return new JaxRsMutableHeadersMultivaluedMap(mutableHttpHeaders);
    }
}
