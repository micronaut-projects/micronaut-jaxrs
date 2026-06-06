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

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionError;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.ConvertibleMultiValuesMap;
import io.micronaut.core.execution.CompletableFutureExecutionFlow;
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
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.multipart.RawFormField;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.http.server.multipart.FormRouteCompleter;
import io.micronaut.http.uri.QueryStringDecoder;
import io.micronaut.jaxrs.common.JaxRsMultipart;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.io.InputStream;
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

    private final BeanProvider<FormFactory> formFactory;

    /**
     * Constructor.
     *
     * @param conversionService       conversion service
     * @param paramConverterProviders param converter providers
     * @param formFactory             form factory
     */
    public FormParamArgumentBinder(ConversionService conversionService,
                                   List<ParamConverterProvider> paramConverterProviders,
                                   BeanProvider<FormFactory> formFactory) {
        super(conversionService, paramConverterProviders);
        this.formFactory = formFactory;
    }

    private FormParamArgumentBinder(ConversionService conversionService,
                                    Argument<T> argument,
                                    @Nullable ParamConverter<T> paramConverter,
                                    @Nullable ParamConverter<?> elementParamConverter,
                                    BeanProvider<FormFactory> formFactory) {
        super(conversionService, argument, paramConverter, elementParamConverter);
        this.formFactory = formFactory;
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
    protected @Nullable BindingResult<T> bindDirect(ArgumentConversionContext<T> context, HttpRequest<?> source, String parameterName) {
        if (!isMultipartRequest(source) || !(source instanceof FormCapableHttpRequest<?> formRequest) || !formRequest.hasFormBody()) {
            return null;
        }
        CompletableFuture<List<EntityPart>> parts = multipartParts(context.getArgument(), source, formRequest, parameterName);
        if (parts.isDone()) {
            return bindParts(context, parameterName, join(parts));
        }
        return new PendingRequestBindingResult<>() {
            private @Nullable BindingResult<T> result;

            @Override
            public boolean isPending() {
                if (!parts.isDone()) {
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
                    bindingResult = bindParts(context, parameterName, join(parts));
                    result = bindingResult;
                }
                return bindingResult;
            }
        };
    }

    @Override
    protected RequestArgumentBinder<T> createSpecific(Argument<T> argument,
                                                      @Nullable ParamConverter<T> paramConverter,
                                                      @Nullable ParamConverter<?> elementParamConverter) {
        return new FormParamArgumentBinder<>(conversionService, argument, paramConverter, elementParamConverter, formFactory);
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

    private boolean isMultipartRequest(HttpRequest<?> source) {
        return source.getMethod().permitsRequestBody()
            && source.getContentType()
                .map(mediaType -> mediaType.matches(MediaType.MULTIPART_FORM_DATA_TYPE))
                .orElse(false);
    }

    private BindingResult<T> bindParts(ArgumentConversionContext<T> context,
                                       String parameterName,
                                       List<EntityPart> parts) {
        if (parts.isEmpty()) {
            return bindValues(context, parameterName, emptyValues());
        }
        Argument<T> argument = context.getArgument();
        EntityPart part = parts.get(0);
        try {
            if (argument.getType().isInstance(part)) {
                return () -> Optional.of(argument.getType().cast(part));
            }
            if (isEntityPartMultiValue(argument)) {
                return () -> conversionService.convert(parts, argument);
            }
            if (InputStream.class.isAssignableFrom(argument.getType())) {
                InputStream inputStream = part.getContent();
                return () -> conversionService.convert(inputStream, argument);
            }
            if (argument.getType() == byte[].class) {
                byte[] bytes = part.getContent(byte[].class);
                return () -> conversionService.convert(bytes, argument);
            }
            return bindValues(context, parameterName, partValues(parameterName, parts));
        } catch (IOException e) {
            throw new BadRequestException(e);
        }
    }

    private CompletableFuture<List<EntityPart>> multipartParts(Argument<T> argument,
                                                               HttpRequest<?> source,
                                                               FormCapableHttpRequest<?> formRequest,
                                                               String parameterName) {
        CompletableFuture<List<EntityPart>> future = new CompletableFuture<>();
        List<EntityPart> parts = new ArrayList<>();
        FormRouteCompleter completer = formFactory.get().getOrCreateCompleter(source);
        completer.subscribeField(
            parameterName,
            new FormRouteCompleter.SubscriptionMetadata(FormRouteCompleter.SubscriptionMode.WAITS_FOR_FULL, argument)
        ).subscribe(new Subscriber<>() {
            private @Nullable Subscription subscription;
            private int pendingParts;
            private boolean completed;

            @Override
            public void onSubscribe(Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(RawFormField rawFormField) {
                partStarted();
                FormFactory resolvedFormFactory = formFactory.get();
                // FormRouteCompleter may emit fields before multipart conversion
                // finishes. Track in-flight completions so the binding result only
                // completes after the upstream stream and all part conversions finish.
                resolvedFormFactory.completePart(formRequest, rawFormField).onComplete((part, throwable) -> {
                    EntityPart entityPart = null;
                    try {
                        if (throwable != null) {
                            completeExceptionally(throwable);
                            return;
                        }
                        try {
                            entityPart = toEntityPart(part);
                        } catch (Throwable e) {
                            completeExceptionally(e);
                        }
                    } finally {
                        partFinished(entityPart);
                    }
                });
            }

            @Override
            public void onError(Throwable throwable) {
                completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                synchronized (this) {
                    completed = true;
                    completeIfReady();
                }
            }

            private void requestNext() {
                Subscription resolvedSubscription = subscription;
                if (resolvedSubscription != null && !future.isDone()) {
                    resolvedSubscription.request(1);
                }
            }

            private synchronized void partStarted() {
                pendingParts++;
            }

            private void partFinished(@Nullable EntityPart part) {
                boolean shouldRequest;
                synchronized (this) {
                    if (part != null) {
                        parts.add(part);
                    }
                    pendingParts--;
                    completeIfReady();
                    shouldRequest = !completed && !future.isDone();
                }
                if (shouldRequest) {
                    requestNext();
                }
            }

            private synchronized void completeIfReady() {
                if (completed && pendingParts == 0 && !future.isDone()) {
                    future.complete(List.copyOf(parts));
                }
            }

            private void completeExceptionally(Throwable throwable) {
                future.completeExceptionally(throwable);
                Subscription resolvedSubscription = subscription;
                if (resolvedSubscription != null) {
                    resolvedSubscription.cancel();
                }
            }
        });
        BasicHttpAttributes.addRouteWaitsFor(source, CompletableFutureExecutionFlow.just(future));
        return future;
    }

    private @Nullable EntityPart toEntityPart(@Nullable CompletedPart part) throws IOException {
        if (part == null) {
            return null;
        }
        return JaxRsMultipart.entityPart(part);
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
        // Multiple @FormParam arguments share the same raw body. Cache the future
        // on the request so the byte body is split and buffered only once.
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

    private <V> @Nullable V join(CompletableFuture<V> future) {
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

    private ConvertibleMultiValues<String> partValues(String name, List<EntityPart> parts) throws IOException {
        List<String> values = new ArrayList<>(parts.size());
        for (EntityPart part : parts) {
            values.add(part.getContent(String.class));
        }
        return new ConvertibleMultiValuesMap<>(Map.of(name, values), conversionService);
    }

    private ConvertibleMultiValues<String> emptyValues() {
        return new ConvertibleMultiValuesMap<>(Map.of(), conversionService);
    }

    private boolean isEntityPartMultiValue(Argument<?> argument) {
        if (argument.getType().isArray()) {
            return EntityPart.class.isAssignableFrom(argument.getType().getComponentType());
        }
        if (Iterable.class.isAssignableFrom(argument.getType())) {
            Argument<?>[] typeParameters = argument.getTypeParameters();
            return typeParameters.length == 0 || EntityPart.class.isAssignableFrom(typeParameters[0].getType());
        }
        return false;
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
