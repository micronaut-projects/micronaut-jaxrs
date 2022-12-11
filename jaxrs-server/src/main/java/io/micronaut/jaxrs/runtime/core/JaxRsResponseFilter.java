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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.io.Writable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import jakarta.ws.rs.core.StreamingOutput;
import org.reactivestreams.Publisher;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * A filter which retrieves the actual response from the returned JAX-RS Response object.
 *
 * @author graemerocher
 * @since 1.0
 */
@Filter("/**")
@Internal
public class JaxRsResponseFilter implements HttpServerFilter {
    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        return Publishers.map(chain.proceed(request), mutableHttpResponse -> {
            final Object body = mutableHttpResponse.getBody().orElse(null);
            if (body instanceof JaxRsResponse jrs) {
                final MutableHttpResponse<Object> jaxRsResponse = jrs.getResponse();
                mutableHttpResponse.getAttributes().forEach(jaxRsResponse::setAttribute);
                mutableHttpResponse.getHeaders().forEach((name, value) -> {
                    for (String val: value) {
                        jaxRsResponse.header(name, val);
                    }
                });

                final Object b = jaxRsResponse.body();
                if (b instanceof StreamingOutput s) {
                    jaxRsResponse.body(new Writable() {
                        @Override
                        public void writeTo(OutputStream outputStream, @Nullable Charset charset) throws IOException {
                            s.write(outputStream);
                        }

                        @Override
                        public void writeTo(Writer out) {
                            // no-op - handled by OutputStream variant
                        }
                    });
                }
                return jaxRsResponse;
            }
            return mutableHttpResponse;
        });
    }
}
