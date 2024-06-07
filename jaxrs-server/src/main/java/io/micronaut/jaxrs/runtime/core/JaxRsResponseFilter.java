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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;

/**
 * A filter which retrieves the actual response from the returned JAX-RS Response object.
 *
 * @author graemerocher
 * @since 1.0
 */
@ServerFilter("/**")
@Internal
final class JaxRsResponseFilter {

    @ResponseFilter
    MutableHttpResponse<?> alterResponse(HttpRequest<?> request, MutableHttpResponse<?> mutableHttpResponse) {
        final Object body;
        if (request.getMethod() == HttpMethod.HEAD) {
            body = mutableHttpResponse.getAttribute(HttpAttributes.HEAD_BODY).orElse(null);
        } else {
            body = mutableHttpResponse.getBody().orElse(null);
        }
        if (body instanceof JaxRsResponse jrs) {
            final MutableHttpResponse<Object> jaxRsResponse = jrs.getResponse();
            mutableHttpResponse.getAttributes().forEach(jaxRsResponse::setAttribute);
            mutableHttpResponse.getHeaders().forEach((name, value) -> {
                for (String val: value) {
                    jaxRsResponse.header(name, val);
                }
            });
            return jaxRsResponse;
        }
        return mutableHttpResponse;
    }
}
