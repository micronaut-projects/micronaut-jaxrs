/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.ConvertibleMultiValuesMap;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.cookie.Cookie;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A binder for binding arguments annotated with {@link CookieParam}.
 *
 * @param <T> The argument type
 * @since 5.0.1
 */
@Prototype
final class CookieParamArgumentBinder<T> extends AbstractParamArgumentBinder<CookieParam, T> {

    /**
     * Constructor.
     *
     * @param conversionService conversion service
     * @param paramConverterProviders param converter providers
     */
    public CookieParamArgumentBinder(ConversionService conversionService, List<ParamConverterProvider> paramConverterProviders) {
        super(conversionService, paramConverterProviders);
    }

    private CookieParamArgumentBinder(ConversionService conversionService,
                                      Argument<T> argument,
                                      @Nullable ParamConverter<T> paramConverter,
                                      @Nullable ParamConverter<?> elementParamConverter) {
        super(conversionService, argument, paramConverter, elementParamConverter);
    }

    @Override
    public Class<CookieParam> getAnnotationType() {
        return CookieParam.class;
    }

    @Override
    protected ConvertibleMultiValues<String> parameterValues(HttpRequest<?> source, Argument<T> argument) {
        return new ConvertibleMultiValuesMap<>(cookieValues(source), conversionService);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected @Nullable BindingResult<T> bindDirect(ArgumentConversionContext<T> context, HttpRequest<?> source, String parameterName) {
        if (context.getArgument().getType() != jakarta.ws.rs.core.Cookie.class) {
            return null;
        }
        Cookie cookie = findCookie(source, parameterName);
        if (cookie == null) {
            List<String> values = cookieValues(source).get(parameterName);
            String value = valueOrDefault(values == null || values.isEmpty() ? null : values.get(0), context.getArgument());
            if (value == null) {
                return BindingResult.unsatisfied();
            }
            jakarta.ws.rs.core.Cookie defaultCookie = new jakarta.ws.rs.core.Cookie.Builder(parameterName)
                .value(value)
                .build();
            return () -> Optional.of((T) defaultCookie);
        }
        jakarta.ws.rs.core.Cookie result = new jakarta.ws.rs.core.Cookie.Builder(cookie.getName())
            .value(cookie.getValue())
            .path(cookie.getPath())
            .domain(cookie.getDomain())
            .build();
        return () -> Optional.of((T) result);
    }

    @Override
    protected RequestArgumentBinder<T> createSpecific(Argument<T> argument,
                                                      @Nullable ParamConverter<T> paramConverter,
                                                      @Nullable ParamConverter<?> elementParamConverter) {
        return new CookieParamArgumentBinder<>(conversionService, argument, paramConverter, elementParamConverter);
    }

    @Override
    protected RuntimeException conversionException(RuntimeException exception) {
        return new BadRequestException(exception);
    }

    private Map<CharSequence, List<String>> cookieValues(HttpRequest<?> source) {
        Map<CharSequence, List<String>> values = new LinkedHashMap<>();
        try {
            for (Cookie cookie : source.getCookies().getAll()) {
                values.put(cookie.getName(), List.of(cookie.getValue()));
            }
        } catch (UnsupportedOperationException ignored) {
            // Some synthetic client request implementations expose cookies only as headers.
        }
        if (values.isEmpty()) {
            for (String header : source.getHeaders().getAll(HttpHeaders.COOKIE)) {
                addCookieHeaderValues(values, header);
            }
        }
        return values;
    }

    private @Nullable Cookie findCookie(HttpRequest<?> source, String parameterName) {
        try {
            return source.getCookies().findCookie(parameterName).orElse(null);
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static void addCookieHeaderValues(Map<CharSequence, List<String>> values, String header) {
        for (String cookie : header.split(";")) {
            String trimmed = cookie.trim();
            int separator = trimmed.indexOf('=');
            if (separator > 0) {
                values.put(trimmed.substring(0, separator), List.of(trimmed.substring(separator + 1)));
            }
        }
    }
}
