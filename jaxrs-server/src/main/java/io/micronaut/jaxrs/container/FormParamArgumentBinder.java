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
import io.micronaut.core.convert.ConversionError;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.ConvertibleMultiValuesMap;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.type.Argument;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.bind.binders.PendingRequestBindingResult;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.CloseableAvailableByteBody;
import io.micronaut.http.body.InternalByteBody;
import io.micronaut.http.uri.QueryStringDecoder;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A binder for binding arguments annotated with {@link FormParam}.
 *
 * @param <T> The argument type
 */
@Prototype
final class FormParamArgumentBinder<T> extends AbstractParamArgumentBinder<FormParam, T> {
    private static final String RAW_FORM_BODY = FormParamArgumentBinder.class.getName() + ".RAW_FORM_BODY";

    /**
     * Constructor.
     *
     * @param conversionService       conversion service
     * @param paramConverterProviders param converter providers
     */
    public FormParamArgumentBinder(ConversionService conversionService, List<ParamConverterProvider> paramConverterProviders) {
        super(conversionService, paramConverterProviders);
    }

    private FormParamArgumentBinder(ConversionService conversionService,
                                    Argument<T> argument,
                                    @Nullable ParamConverter<T> paramConverter,
                                    @Nullable ParamConverter<?> elementParamConverter) {
        super(conversionService, argument, paramConverter, elementParamConverter);
    }

    @Override
    public Class<FormParam> getAnnotationType() {
        return FormParam.class;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        Argument<T> argument = context.getArgument();
        if (!isBindable(argument, source)) {
            return BindingResult.unsatisfied();
        }
        String parameterName = resolvedParameterName(argument);
        BindingResult<T> directResult = bindDirect(context, source, parameterName);
        if (directResult != null) {
            return directResult;
        }
        if (!isFormRequest(source)) {
            return bindValues(context, parameterName, emptyValues());
        }
        CompletableFuture<String> rawBody = rawFormBody(source);
        if (rawBody.isDone()) {
            return bindRawBody(context, source, parameterName, join(rawBody));
        }
        return new PendingRequestBindingResult<>() {
            private @Nullable BindingResult<T> result;

            @Override
            public boolean isPending() {
                if (!rawBody.isDone()) {
                    return true;
                }
                result();
                return false;
            }

            @Override
            public Optional<T> getValue() {
                return result().getValue();
            }

            @Override
            public List<ConversionError> getConversionErrors() {
                return result().getConversionErrors();
            }

            private BindingResult<T> result() {
                BindingResult<T> bindingResult = result;
                if (bindingResult == null) {
                    bindingResult = bindRawBody(context, source, parameterName, join(rawBody));
                    result = bindingResult;
                }
                return bindingResult;
            }
        };
    }

    @Override
    protected ConvertibleMultiValues<String> parameterValues(HttpRequest<?> source, Argument<T> argument) {
        return emptyValues();
    }

    @Override
    protected RequestArgumentBinder<T> createSpecific(Argument<T> argument,
                                                      @Nullable ParamConverter<T> paramConverter,
                                                      @Nullable ParamConverter<?> elementParamConverter) {
        return new FormParamArgumentBinder<>(conversionService, argument, paramConverter, elementParamConverter);
    }

    @Override
    protected RuntimeException conversionException(RuntimeException exception) {
        return new BadRequestException(exception);
    }

    private BindingResult<T> bindRawBody(ArgumentConversionContext<T> context,
                                         HttpRequest<?> source,
                                         String parameterName,
                                         String rawBody) {
        boolean decode = !context.getArgument().getAnnotationMetadata().hasAnnotation(Encoded.class);
        return bindValues(context, parameterName, formParameters(rawBody, source.getCharacterEncoding(), decode));
    }

    private boolean isFormRequest(HttpRequest<?> source) {
        return source.getMethod().permitsRequestBody()
            && source.getContentType().map(MediaType.APPLICATION_FORM_URLENCODED_TYPE::equals).orElse(false);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<String> rawFormBody(HttpRequest<?> source) {
        Optional<CompletableFuture> existing = source.getAttribute(RAW_FORM_BODY, CompletableFuture.class);
        if (existing.isPresent()) {
            return (CompletableFuture<String>) existing.get();
        }
        if (!(source instanceof ServerHttpRequest<?> serverHttpRequest)) {
            return CompletableFuture.completedFuture(source.getBody(String.class).orElse(""));
        }
        if (serverHttpRequest.byteBody().expectedLength().orElse(-1) == 0) {
            return CompletableFuture.completedFuture("");
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        ByteBody body = serverHttpRequest.byteBody().split(ByteBody.SplitBackpressureMode.FASTEST);
        ExecutionFlow<?> flow = InternalByteBody.bufferFlow(body)
            .map(availableBody -> {
                future.complete(toStringAndClose(availableBody, source.getCharacterEncoding()));
                return null;
            })
            .onErrorResume(exception -> {
                future.completeExceptionally(exception);
                return ExecutionFlow.error(exception);
            });
        source.setAttribute(RAW_FORM_BODY, future);
        BasicHttpAttributes.addRouteWaitsFor(source, flow);
        return future;
    }

    private String toStringAndClose(CloseableAvailableByteBody body, Charset charset) {
        try (body) {
            return body.toString(charset);
        }
    }

    private String join(CompletableFuture<String> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BadRequestException(cause);
        }
    }

    private ConvertibleMultiValues<String> formParameters(String rawBody, Charset charset, boolean decode) {
        Map<CharSequence, List<String>> values = decode ? decodedFormParameters(rawBody, charset) : encodedFormParameters(rawBody, charset);
        return new ConvertibleMultiValuesMap<>(values, conversionService);
    }

    private ConvertibleMultiValues<String> emptyValues() {
        return new ConvertibleMultiValuesMap<>(Map.of(), conversionService);
    }

    private static Map<CharSequence, List<String>> decodedFormParameters(String rawBody, Charset charset) {
        Map<CharSequence, List<String>> values = new LinkedHashMap<>();
        new QueryStringDecoder(rawBody, charset, false).parameters().forEach(values::put);
        return values;
    }

    private static Map<CharSequence, List<String>> encodedFormParameters(String rawBody, Charset charset) {
        Map<CharSequence, List<String>> values = new LinkedHashMap<>();
        int nameStart = 0;
        int valueStart = -1;
        for (int i = 0; i <= rawBody.length(); i++) {
            boolean end = i == rawBody.length();
            char c = end ? '&' : rawBody.charAt(i);
            if (c == '=' && valueStart < nameStart) {
                valueStart = i + 1;
            } else if (c == '&' || c == ';') {
                addEncodedParameter(values, rawBody, charset, nameStart, valueStart, i);
                nameStart = i + 1;
                valueStart = -1;
            }
        }
        return values;
    }

    private static void addEncodedParameter(Map<CharSequence, List<String>> values,
                                            String rawBody,
                                            Charset charset,
                                            int nameStart,
                                            int valueStart,
                                            int valueEnd) {
        if (nameStart >= valueEnd) {
            return;
        }
        int nameEnd = valueStart > nameStart ? valueStart - 1 : valueEnd;
        String name = URLDecoder.decode(rawBody.substring(nameStart, nameEnd), charset);
        String value = valueStart > nameStart ? rawBody.substring(valueStart, valueEnd) : "";
        values.computeIfAbsent(name, ignored -> new ArrayList<>(1)).add(value);
    }
}
