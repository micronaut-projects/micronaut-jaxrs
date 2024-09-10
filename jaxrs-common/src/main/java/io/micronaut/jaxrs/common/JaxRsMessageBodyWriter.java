/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.jaxrs.common;

import io.micronaut.context.BeanRegistration;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * The reader remapped {@link MessageBodyWriter}.
 *
 * @param <T> The type
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
@EachBean(value = jakarta.ws.rs.ext.MessageBodyWriter.class, remapGenerics = @EachBean.RemapGeneric(name = "T", type = MessageBodyWriter.class))
public final class JaxRsMessageBodyWriter<T> implements MessageBodyWriter<T> {

    private final List<MediaType> produces;
    private final jakarta.ws.rs.ext.MessageBodyWriter<T> delegate;
    private final List<WriterInterceptor> writerInterceptors;
    @Nullable
    private final RouteReturnTypeProvider routeReturnTypeProvider;

    @Inject
    public JaxRsMessageBodyWriter(BeanRegistration<jakarta.ws.rs.ext.MessageBodyWriter<T>> beanRegistration,
                                  List<WriterInterceptor> writerInterceptors,
                                  @Nullable RouteReturnTypeProvider routeReturnTypeProvider) {
        this(beanRegistration.getBeanDefinition(), beanRegistration.bean(), writerInterceptors, routeReturnTypeProvider);
    }

    public JaxRsMessageBodyWriter(AnnotationMetadata annotationMetadata,
                                  jakarta.ws.rs.ext.MessageBodyWriter<T> delegate,
                                  List<WriterInterceptor> writerInterceptors,
                                  @Nullable RouteReturnTypeProvider routeReturnTypeProvider) {
        this.produces = asMediaTypes(annotationMetadata);
        this.delegate = delegate;
        this.writerInterceptors = writerInterceptors;
        this.routeReturnTypeProvider = routeReturnTypeProvider;
        JaxRsUtils.sortByPriority(writerInterceptors);
    }

    public JaxRsMessageBodyWriter(Argument<?> writerArgument,
                                  jakarta.ws.rs.ext.MessageBodyWriter<T> delegate,
                                  List<WriterInterceptor> writerInterceptors) {
        this(writerArgument.getAnnotationMetadata(), delegate, writerInterceptors, null);
    }

    private static List<MediaType> asMediaTypes(AnnotationMetadata annotationMetadata) {
        AnnotationValue<Produces> producesAnnotationValue = annotationMetadata.getAnnotation(Produces.class);
        if (producesAnnotationValue == null) {
            return List.of();
        }
        return Arrays.stream(producesAnnotationValue.stringValues())
            .map(MediaType::of)
            .toList();
    }

    @Override
    public boolean isWriteable(@NonNull Argument<T> type, @Nullable MediaType mediaType) {
        return delegate.isWriteable(type.getType(), type.asType(), type.getAnnotationMetadata().synthesizeAll(), JaxRsUtils.convert(mediaType));
    }

    @Override
    public void writeTo(@NonNull Argument<T> type,
                        @NonNull MediaType mediaType,
                        T object,
                        @NonNull MutableHeaders outgoingHeaders,
                        @NonNull OutputStream outputStream) throws CodecException {
        try {
            if (type.getAnnotationMetadata().isEmpty() && routeReturnTypeProvider != null) {
                // JaxRs expects all the annotation from the method
                Argument<?> argument = routeReturnTypeProvider.provideReturnType();
                if (argument != null && !argument.getAnnotationMetadata().isEmpty()) {
                    type = Argument.of(type.getType(), argument.getAnnotationMetadata(), type.getTypeParameters());
                }
            }
            Iterator<WriterInterceptor> iterator = writerInterceptors.iterator();
            JaxRsMutableObjectHeadersMultivaluedMap httpHeaders = new JaxRsMutableObjectHeadersMultivaluedMap(outgoingHeaders);
            if (iterator.hasNext()) {
                JaxRsWriterInterceptorContext context = new JaxRsWriterInterceptorContext(iterator,
                    ctx -> delegate.writeTo(
                        (T) ctx.getEntity(),
                        ctx.getType(),
                        ctx.getGenericType(),
                        ctx.getAnnotations(),
                        ctx.getMediaType(),
                        httpHeaders,
                        ctx.getOutputStream()
                    ),
                    type,
                    JaxRsUtils.convert(mediaType),
                    httpHeaders,
                    object,
                    outputStream
                );
                iterator.next().aroundWriteTo(context);
                return;
            }
            delegate.writeTo(object,
                type.getType(),
                type.asType(),
                type.getAnnotationMetadata().synthesizeAll(),
                JaxRsUtils.convert(mediaType),
                httpHeaders,
                outputStream
            );
            if (!httpHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
                if (mediaType == null || JaxRsUtils.convert(mediaType).isWildcardType()) {
                    if (produces.size() == 1) {
                        httpHeaders.add(HttpHeaders.CONTENT_TYPE, produces.get(0).toString());
                    }
                } else {
                    httpHeaders.add(HttpHeaders.CONTENT_TYPE, mediaType.toString());
                }
            }
        } catch (IOException e) {
            throw new CodecException("Cannot write to", e);
        }
    }

}
