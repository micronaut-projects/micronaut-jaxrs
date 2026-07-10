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
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionError;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.execution.CompletableFutureExecutionFlow;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.io.buffer.ReferenceCounted;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.PendingRequestBindingResult;
import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.CloseableAvailableByteBody;
import io.micronaut.http.body.InternalByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.context.ServerHttpRequestContext;
import io.micronaut.http.form.FormCapableHttpRequest;
import io.micronaut.http.multipart.RawFormField;
import io.micronaut.http.server.multipart.FormFactory;
import io.micronaut.http.server.exceptions.UnsupportedMediaException;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.HttpMessageEntityReader;
import io.micronaut.jaxrs.common.JaxRsMultipart;
import io.micronaut.jaxrs.common.JaxRsIOException;
import io.micronaut.jaxrs.common.JaxRsNoContentPlaceholderProvider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.EntityPart;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Binds implicit Jakarta REST entity parameters through message body readers.
 *
 * @param <T> The entity type
 */
@Singleton
final class JaxRsEntityArgumentBinder<T> implements AnnotatedRequestArgumentBinder<JaxRsEntity, T> {
    static final String NO_CONTENT_BAD_REQUEST = JaxRsEntityArgumentBinder.class.getName() + ".NO_CONTENT_BAD_REQUEST";
    private static final Map<Class<?>, Supplier<?>> NO_CONTENT_PLACEHOLDERS = Map.ofEntries(
        Map.entry(Boolean.class, () -> false),
        Map.entry(Character.class, () -> '\0'),
        Map.entry(Byte.class, () -> (byte) 0),
        Map.entry(Short.class, () -> (short) 0),
        Map.entry(Integer.class, () -> 0),
        Map.entry(Long.class, () -> 0L),
        Map.entry(Float.class, () -> 0F),
        Map.entry(Double.class, () -> 0D),
        Map.entry(BigDecimal.class, () -> BigDecimal.ZERO),
        Map.entry(BigInteger.class, () -> BigInteger.ZERO),
        Map.entry(AtomicInteger.class, AtomicInteger::new),
        Map.entry(AtomicLong.class, AtomicLong::new)
    );
    private static final MediaType DEFAULT_ENTITY_MEDIA_TYPE = MediaType.APPLICATION_OCTET_STREAM_TYPE;

    private final ConversionService conversionService;
    private final MessageBodyHandlerRegistry bodyHandlerRegistry;
    private final JaxRsContainerMessageBodyHandlerRegistry jaxRsHandlerRegistry;
    private final JaxRsMessageBodyReaders<T> jaxRsMessageBodyReaders;
    private final JaxRsDynamicFeatureRegistry dynamicFeatureRegistry;
    private final BeanProvider<FormFactory> formFactory;
    private final List<JaxRsNoContentPlaceholderProvider> noContentPlaceholderProviders;

    JaxRsEntityArgumentBinder(ConversionService conversionService,
                              MessageBodyHandlerRegistry bodyHandlerRegistry,
                              JaxRsContainerMessageBodyHandlerRegistry jaxRsHandlerRegistry,
                              JaxRsMessageBodyReaders<T> jaxRsMessageBodyReaders,
                              JaxRsDynamicFeatureRegistry dynamicFeatureRegistry,
                              BeanProvider<FormFactory> formFactory,
                              List<JaxRsNoContentPlaceholderProvider> noContentPlaceholderProviders) {
        this.conversionService = conversionService;
        this.bodyHandlerRegistry = bodyHandlerRegistry;
        this.jaxRsHandlerRegistry = jaxRsHandlerRegistry;
        this.jaxRsMessageBodyReaders = jaxRsMessageBodyReaders;
        this.dynamicFeatureRegistry = dynamicFeatureRegistry;
        this.formFactory = formFactory;
        this.noContentPlaceholderProviders = noContentPlaceholderProviders;
    }

    @Override
    public Class<JaxRsEntity> getAnnotationType() {
        return JaxRsEntity.class;
    }

    @Override
    public ArgumentBinder.BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        if (!source.getMethod().permitsRequestBody()) {
            return ArgumentBinder.BindingResult.unsatisfied();
        }
        if (source instanceof FormCapableHttpRequest<?> formRequest && isEntityPartList(context.getArgument(), source)) {
            return bindMultipartEntityParts(context, source, formRequest);
        }
        if (!(source instanceof ServerHttpRequest<?> serverHttpRequest)) {
            return bindLegacyBody(context, source);
        }
        MediaType mediaType = source.getContentType().orElse(DEFAULT_ENTITY_MEDIA_TYPE);
        if (serverHttpRequest.byteBody().expectedLength().orElse(-1) == 0) {
            return bindEmptyBody(context, serverHttpRequest, mediaType);
        }
        ByteBody body = serverHttpRequest.byteBody().split(ByteBody.SplitBackpressureMode.FASTEST);
        ExecutionFlow<? extends CloseableAvailableByteBody> buffered = InternalByteBody.bufferFlow(body);
        PendingEntityBindingResult<T> bindingResult = new PendingEntityBindingResult<>();
        ExecutionFlow<?> flow = buffered.flatMap(availableBody ->
            PropagatedContext.getOrEmpty().plus(new ServerHttpRequestContext(serverHttpRequest)).propagate(() -> {
                try {
                    bindingResult.complete(bindAvailableBody(context, source, mediaType, availableBody, false));
                    return ExecutionFlow.just(null);
                } catch (Throwable e) {
                    return ExecutionFlow.error(e);
                }
            }));
        BasicHttpAttributes.addRouteWaitsFor(source, flow);
        return bindingResult;
    }

    private ArgumentBinder.BindingResult<T> bindMultipartEntityParts(ArgumentConversionContext<T> context,
                                                                     HttpRequest<?> source,
                                                                     FormCapableHttpRequest<?> formRequest) {
        CompletableFuture<List<EntityPart>> future = collectMultipartEntityParts(formRequest);
        BasicHttpAttributes.addRouteWaitsFor(source, CompletableFutureExecutionFlow.just(future));
        return new PendingRequestBindingResult<>() {
            @Override
            public boolean isPending() {
                return !future.isDone();
            }

            @Override
            public Optional<T> getValue() {
                if (!future.isDone()) {
                    return Optional.empty();
                }
                return conversionService.convert(join(future), context);
            }

            @Override
            public List<ConversionError> getConversionErrors() {
                return List.of();
            }
        };
    }

    private CompletableFuture<List<EntityPart>> collectMultipartEntityParts(FormCapableHttpRequest<?> formRequest) {
        CompletableFuture<List<EntityPart>> future = new CompletableFuture<>();
        List<EntityPart> parts = new ArrayList<>();
        // EntityPart conversion relies on Micronaut's multipart pipeline; keep the
        // binder pending until every raw form field has been completed by FormFactory.
        formRequest.getRawFormFields().subscribe(new Subscriber<>() {
            private @Nullable Subscription subscription;

            @Override
            public void onSubscribe(Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(RawFormField rawFormField) {
                formFactory.get().completePart(formRequest, rawFormField).onComplete((part, throwable) -> {
                    if (throwable != null) {
                        completeExceptionally(throwable);
                        return;
                    }
                    try {
                        if (part != null) {
                            parts.add(JaxRsMultipart.entityPart(part));
                        }
                    } catch (Throwable e) {
                        completeExceptionally(e);
                        return;
                    }
                    Subscription resolvedSubscription = subscription;
                    if (resolvedSubscription != null && !future.isDone()) {
                        resolvedSubscription.request(1);
                    }
                });
            }

            @Override
            public void onError(Throwable throwable) {
                completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(List.copyOf(parts));
            }

            private void completeExceptionally(Throwable throwable) {
                future.completeExceptionally(throwable);
                Subscription resolvedSubscription = subscription;
                if (resolvedSubscription != null) {
                    resolvedSubscription.cancel();
                }
            }
        });
        return future;
    }

    private ArgumentBinder.BindingResult<T> bindLegacyBody(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        Optional<?> body = source.getBody();
        if (body.isPresent()) {
            return () -> conversionService.convert(body.get(), context);
        }
        return ArgumentBinder.BindingResult.empty();
    }

    private ArgumentBinder.BindingResult<T> bindEmptyBody(ArgumentConversionContext<T> context,
                                                          ServerHttpRequest<?> source,
                                                          @Nullable MediaType mediaType) {
        Supplier<?> placeholder = emptyBodyPlaceholder(context.getArgument());
        if (placeholder != null) {
            return noContentBadRequest(source, placeholder);
        }
        if (!hasJaxRsReader(context.getArgument(), source, mediaType)) {
            return ArgumentBinder.BindingResult.empty();
        }
        PendingEntityBindingResult<T> bindingResult = new PendingEntityBindingResult<>();
        ExecutionFlow<?> flow = PropagatedContext.getOrEmpty().plus(new ServerHttpRequestContext(source)).propagate(() -> {
            try {
                bindingResult.complete(bindAvailableBody(context, source, mediaType, source.byteBodyFactory().createEmpty(), true));
                return ExecutionFlow.just(null);
            } catch (Throwable e) {
                return ExecutionFlow.error(e);
            }
        });
        BasicHttpAttributes.addRouteWaitsFor(source, flow);
        return bindingResult;
    }

    private ArgumentBinder.BindingResult<T> bindAvailableBody(ArgumentConversionContext<T> context,
                                                              HttpRequest<?> source,
                                                              @Nullable MediaType mediaType,
                                                              CloseableAvailableByteBody body,
                                                              boolean requireJaxRsReader) {
        try (body) {
            Optional<MessageBodyReader<T>> reader = findReader(context.getArgument(), source, mediaType);
            if (reader.isPresent()) {
                return read(context, source, mediaType, reader.get(), body.toByteBuffer());
            }
            if (requireJaxRsReader) {
                return ArgumentBinder.BindingResult.empty();
            }
            if (mediaType != null) {
                throw new UnsupportedMediaException(mediaType.toString(), List.of());
            }
            Optional<T> converted = conversionService.convert(body.toByteArray(), byte[].class, context.getArgument());
            return () -> converted;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<MessageBodyReader<T>> findReader(Argument<T> argument, HttpRequest<?> source, @Nullable MediaType mediaType) {
        List<MediaType> mediaTypes = mediaTypes(mediaType);
        Optional<MessageBodyReader<T>> bodyReader = (Optional) bodyHandlerRegistry.findReader(argument, mediaTypes);
        if ((!dynamicFeatureRegistry.components(source).readerInterceptors().isEmpty() || jaxRsMessageBodyReaders.hasReaderInterceptors() || jaxRsHandlerRegistry.findReader(argument, mediaTypes).isPresent())
            && (bodyReader.isEmpty() || !(bodyReader.get() instanceof JaxRsMessageBodyReaders<?>))) {
            return Optional.of(jaxRsMessageBodyReaders);
        }
        return bodyReader;
    }

    private boolean hasJaxRsReader(Argument<T> argument, HttpRequest<?> source, @Nullable MediaType mediaType) {
        return !dynamicFeatureRegistry.components(source).readerInterceptors().isEmpty()
            || jaxRsMessageBodyReaders.hasReaderInterceptors()
            || jaxRsHandlerRegistry.findReader(argument, mediaTypes(mediaType)).isPresent();
    }

    private ArgumentBinder.BindingResult<T> read(ArgumentConversionContext<T> context,
                                                 HttpRequest<?> source,
                                                 @Nullable MediaType mediaType,
                                                 MessageBodyReader<T> reader,
                                                 ByteBuffer<?> byteBuffer) {
        boolean success = false;
        try {
            T result = reader.read(context.getArgument(), mediaType, source.getHeaders(), byteBuffer);
            success = true;
            return () -> Optional.ofNullable(result);
        } catch (JaxRsIOException e) {
            if (HttpMessageEntityReader.isNoContentException(e.getCause())) {
                Supplier<?> placeholder = emptyBodyPlaceholder(context.getArgument());
                if (placeholder != null) {
                    return noContentBadRequest(source, placeholder);
                }
            }
            throw e;
        } catch (CodecException e) {
            if (e.getCause() instanceof Exception cause) {
                context.reject(cause);
            } else {
                context.reject(e);
            }
            return ArgumentBinder.BindingResult.empty();
        } finally {
            if (!success && byteBuffer instanceof ReferenceCounted referenceCounted) {
                referenceCounted.release();
            }
        }
    }

    private static List<MediaType> mediaTypes(@Nullable MediaType mediaType) {
        return List.of(mediaType == null ? DEFAULT_ENTITY_MEDIA_TYPE : mediaType);
    }

    private static boolean isEntityPartList(Argument<?> argument, HttpRequest<?> source) {
        if (!List.class.isAssignableFrom(argument.getType())) {
            return false;
        }
        if (source.getContentType().map(mediaType -> mediaType.matches(MediaType.MULTIPART_FORM_DATA_TYPE)).orElse(false)) {
            Argument<?>[] typeParameters = argument.getTypeParameters();
            return typeParameters.length == 0 || typeParameters[0].getType() == EntityPart.class;
        }
        return false;
    }

    private @Nullable Supplier<?> emptyBodyPlaceholder(Argument<?> argument) {
        Supplier<?> placeholder = NO_CONTENT_PLACEHOLDERS.get(ReflectionUtils.getWrapperType(argument.getType()));
        if (placeholder != null) {
            return placeholder;
        }
        for (JaxRsNoContentPlaceholderProvider provider : noContentPlaceholderProviders) {
            Optional<Supplier<?>> resolved = provider.findPlaceholder(argument);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }
        return null;
    }

    private ArgumentBinder.BindingResult<T> noContentBadRequest(HttpRequest<?> source, Supplier<?> placeholder) {
        source.setAttribute(NO_CONTENT_BAD_REQUEST, true);
        T value = (T) placeholder.get();
        return () -> Optional.of(value);
    }

    private static <V> V join(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new CodecException("Cannot read multipart entity parts", cause);
        }
    }

    private static final class PendingEntityBindingResult<T> implements PendingRequestBindingResult<T> {
        private ArgumentBinder.BindingResult<T> result;

        @Override
        public boolean isPending() {
            return result == null;
        }

        @Override
        public Optional<T> getValue() {
            return result == null ? Optional.empty() : result.getValue();
        }

        @Override
        public List<ConversionError> getConversionErrors() {
            return result == null ? List.of() : result.getConversionErrors();
        }

        private void complete(ArgumentBinder.BindingResult<T> result) {
            this.result = result;
        }
    }
}
