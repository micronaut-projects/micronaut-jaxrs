package io.micronaut.jaxrs.runtime.ext.bind;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.CookieValue;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;

import javax.inject.Singleton;
import javax.ws.rs.core.Cookie;
import java.util.Optional;

/**
 * Argument binder for handling cookies.
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
@Internal
public class CookieRequestArgumentBinder implements TypedRequestArgumentBinder<Cookie>, AnnotatedRequestArgumentBinder<CookieValue, Cookie> {

    public static final Argument<Cookie> TYPE = Argument.of(Cookie.class);

    @Override
    public Argument<Cookie> argumentType() {
        return TYPE;
    }

    @Override
    public BindingResult<Cookie> bind(ArgumentConversionContext<Cookie> context, HttpRequest<?> source) {
        final io.micronaut.http.cookie.Cookie cookie = source.getCookies()
                .findCookie(context.getAnnotationMetadata().stringValue(CookieValue.class)
                        .orElse(context.getArgument().getName())).orElse(null);
        if (cookie != null) {
            Cookie c = new Cookie(cookie.getName(), cookie.getValue(), cookie.getPath(), cookie.getDomain());
            return () -> Optional.of(c);
        } else {
            return BindingResult.EMPTY;
        }
    }

    @Override
    public Class getAnnotationType() {
        return CookieValue.class;
    }
}
