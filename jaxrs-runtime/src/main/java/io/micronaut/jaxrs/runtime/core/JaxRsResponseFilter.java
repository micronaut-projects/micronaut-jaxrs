package io.micronaut.jaxrs.runtime.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.io.Writable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;

import javax.annotation.Nullable;
import javax.ws.rs.core.StreamingOutput;
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
            if (body instanceof JaxRsResponse) {
                final MutableHttpResponse<Object> jaxRsResponse = ((JaxRsResponse) body).getResponse();
                final Object b = jaxRsResponse.body();
                if (b instanceof StreamingOutput) {
                    StreamingOutput s = (StreamingOutput) b;
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
