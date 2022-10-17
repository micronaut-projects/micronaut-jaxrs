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
package io.micronaut.jaxrs.runtime.ext.bind;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.CookieValue;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Cookie;

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
