package io.micronaut.jaxrs.runtime.ext.bind;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;

import javax.inject.Singleton;
import javax.ws.rs.core.HttpHeaders;
import java.util.Optional;

/**
 * Handles binding of the JAX-RS {@code HttpHeaders} type.
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
public class HttpHeadersBinder implements TypedRequestArgumentBinder<HttpHeaders> {

    public static final Argument<HttpHeaders> TYPE = Argument.of(HttpHeaders.class);

    @Override
    public Argument<HttpHeaders> argumentType() {
        return TYPE;
    }

    @Override
    public BindingResult<HttpHeaders> bind(ArgumentConversionContext<HttpHeaders> context, HttpRequest<?> source) {
        return () -> Optional.of(new JaxRsHttpHeaders(source.getHeaders()));
    }
}
